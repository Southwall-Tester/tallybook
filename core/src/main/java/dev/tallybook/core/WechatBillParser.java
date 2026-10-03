package dev.tallybook.core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses a known WeChat payment-detail response without evaluating JavaScript.
 * This does not call private APIs, enumerate bills, or bypass authentication.
 * The envelope was independently implemented after inspecting AutoAccounting's
 * WebViewHooker.kt; fixtures are entirely synthetic and include no account tokens.
 */
public final class WechatBillParser {
    public static final int MAX_INPUT_BYTES = 256 * 1024;
    private static final String BRIDGE_PREFIX = "WeixinJSBridge._handleMessageFromWeixin(";
    private static final List<String> TRADE_LABELS = Arrays.asList("交易单号", "转账单号", "微信支付订单号");
    private static final List<String> TIME_LABELS = Arrays.asList("支付时间", "交易时间", "转账时间", "收款时间", "入账时间");
    private static final List<String> PAYMENT_LABELS = Arrays.asList("支付方式", "付款方式", "收款方式");
    private static final List<String> DESCRIPTION_LABELS = Arrays.asList("商品", "商品说明", "交易说明", "转账说明");
    private static final ZoneId WECHAT_LOCAL_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter[] LOCAL_TIME_FORMATS = {
            DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withResolverStyle(ResolverStyle.STRICT),
            DateTimeFormatter.ofPattern("uuuu年MM月dd日 HH:mm:ss").withResolverStyle(ResolverStyle.STRICT)
    };

    private WechatBillParser() {}

    public static ParseResult parse(String input) {
        try {
            if (input == null || input.trim().isEmpty()) throw problem("EMPTY_INPUT", "没有可解析的数据");
            if (input.length() > MAX_INPUT_BYTES || input.getBytes(StandardCharsets.UTF_8).length > MAX_INPUT_BYTES) {
                throw problem("INPUT_TOO_LARGE", "数据超过 256 KiB 限制");
            }
            String jsonText = unwrap(input.trim());
            JSONObject root = object(jsonText);
            JSONObject detail;
            if (root.has("__json_message")) {
                JSONObject message = root.optJSONObject("__json_message");
                JSONObject params = message == null ? null : message.optJSONObject("__params");
                if (params == null) throw problem("UNSUPPORTED_SCHEMA", "不是支持的微信支付回传结构");
                if (!"nativeWXPayCgiTunnel:ok".equals(params.opt("err_msg"))) {
                    throw problem("REMOTE_ERROR", "微信支付回传未成功");
                }
                Object response = params.opt("respbuf");
                if (!(response instanceof String)) throw problem("UNSUPPORTED_SCHEMA", "缺少账单详情响应");
                detail = object((String) response);
            } else {
                detail = root;
            }
            if (detail.has("ret_code") && !isZeroInteger(detail.opt("ret_code"))) {
                throw problem("REMOTE_ERROR", "账单详情返回未成功");
            }
            JSONObject header = detail.optJSONObject("header");
            JSONArray preview = detail.optJSONArray("preview");
            if (header == null || preview == null || preview.length() == 0 || preview.length() > 1000) {
                throw problem("UNSUPPORTED_SCHEMA", "仅支持已识别的微信单笔账单详情，暂不解析列表");
            }
            String counterparty = requiredString(header, "nickname", "缺少交易对象");
            long amountMinor = amount(requiredString(header, "fee", "缺少交易金额"));
            Map<String, List<FieldValue>> fields = readFields(preview);
            String status = distinctText(fields, Arrays.asList("当前状态"));
            String tradeId = distinctText(fields, TRADE_LABELS);
            String paymentMethod = distinctText(fields, PAYMENT_LABELS);
            String description = distinctText(fields, DESCRIPTION_LABELS);
            long occurredAt = transactionTime(fields);

            List<String> reviewReasons = new ArrayList<>();
            if (tradeId.isEmpty()) reviewReasons.add("缺少交易单号，指纹可能合并相同记录");
            if (!Transaction.isConfirmedStatus(status)) {
                if (status.isEmpty()) reviewReasons.add("缺少交易状态");
                else reviewReasons.add("交易状态需要核对：" + status);
            }
            if (Transaction.isTransferLike(counterparty, description) || fields.containsKey("转账单号")) {
                reviewReasons.add("转账、充值、提现或还款需核对是否属于内部资金转移");
            }
            Transaction transaction = new Transaction(tradeId, counterparty, status, paymentMethod,
                    description, amountMinor, occurredAt, !reviewReasons.isEmpty(), String.join("；", reviewReasons));
            return ParseResult.success(transaction);
        } catch (ParseProblem e) {
            return ParseResult.failure(e.code, e.getMessage());
        } catch (IllegalArgumentException e) {
            return ParseResult.failure("INVALID_FIELD", "账单字段不符合格式或范围要求");
        } catch (Exception e) {
            return ParseResult.failure("INVALID_JSON", "数据不是完整有效的受支持 JSON");
        }
    }

    private static String unwrap(String input) throws ParseProblem {
        String candidate = input;
        if (candidate.startsWith("javascript:")) candidate = candidate.substring("javascript:".length());
        if (candidate.startsWith(BRIDGE_PREFIX)) {
            candidate = candidate.substring(BRIDGE_PREFIX.length()).trim();
            if (candidate.endsWith(";")) candidate = candidate.substring(0, candidate.length() - 1).trim();
            if (!candidate.endsWith(")")) throw problem("INVALID_WRAPPER", "微信桥接回传未完整结束");
            return candidate.substring(0, candidate.length() - 1).trim();
        }
        if (candidate.startsWith("{")) return candidate;
        throw problem("UNSUPPORTED_SCHEMA", "不是支持的微信桥接回传或账单详情");
    }

    private static JSONObject object(String json) throws Exception {
        try {
            return JsonInput.object(json);
        } catch (Exception e) {
            throw problem("INVALID_JSON", "必须提供完整、嵌套不超过 32 层的严格 JSON 对象");
        }
    }

    private static Map<String, List<FieldValue>> readFields(JSONArray preview) throws Exception {
        Map<String, List<FieldValue>> result = new LinkedHashMap<>();
        for (int i = 0; i < preview.length(); i++) {
            JSONObject row = preview.optJSONObject(i);
            if (row == null) throw problem("UNSUPPORTED_SCHEMA", "账单详情行格式不匹配");
            JSONObject label = row.optJSONObject("label");
            if (label == null) {
                if (row.has("label")) throw problem("UNSUPPORTED_SCHEMA", "账单标签格式不匹配");
                continue; // Known detail responses contain unlabelled separator rows.
            }
            String labelText = requiredString(label, "name", "账单标签缺少名称");
            if (!isKnownLabel(labelText)) continue;
            JSONArray values = row.optJSONArray("value");
            if (values == null) throw problem("UNSUPPORTED_SCHEMA", "账单值格式不匹配");
            List<FieldValue> items = result.computeIfAbsent(labelText, key -> new ArrayList<>());
            for (int j = 0; j < values.length(); j++) {
                JSONObject value = values.optJSONObject(j);
                if (value == null) throw problem("UNSUPPORTED_SCHEMA", "账单值格式不匹配");
                Object text = value.opt("name");
                if (!(text instanceof String)) throw problem("INVALID_FIELD", "账单值必须为文本");
                Object timestamp = value.opt("is_timestamp");
                if (value.has("is_timestamp") && !(timestamp instanceof Boolean)) {
                    throw problem("INVALID_TIME", "时间标记格式不匹配");
                }
                String trimmed = ((String) text).trim();
                if (!trimmed.isEmpty()) items.add(new FieldValue(trimmed, Boolean.TRUE.equals(timestamp)));
            }
        }
        return result;
    }

    private static boolean isKnownLabel(String label) {
        return label.equals("当前状态") || TRADE_LABELS.contains(label) || TIME_LABELS.contains(label)
                || PAYMENT_LABELS.contains(label) || DESCRIPTION_LABELS.contains(label);
    }

    private static String distinctText(Map<String, List<FieldValue>> fields, List<String> labels) throws ParseProblem {
        String found = "";
        for (String label : labels) {
            for (FieldValue value : fields.getOrDefault(label, java.util.Collections.emptyList())) {
                if (!found.isEmpty() && !found.equals(value.text)) {
                    throw problem("AMBIGUOUS_FIELD", "存在互相冲突的账单字段，请人工核对");
                }
                found = value.text;
            }
        }
        return found;
    }

    private static long transactionTime(Map<String, List<FieldValue>> fields) throws ParseProblem {
        Long found = null;
        for (String label : TIME_LABELS) {
            for (FieldValue value : fields.getOrDefault(label, java.util.Collections.emptyList())) {
                long parsed = time(value);
                if (found != null && found != parsed) {
                    throw problem("AMBIGUOUS_TIME", "存在不同的交易时间，暂不自动选择");
                }
                found = parsed;
            }
        }
        if (found == null) throw problem("MISSING_TIME", "缺少交易时间，不使用采集时间代替");
        return found;
    }

    private static long time(FieldValue value) throws ParseProblem {
        long result = -1;
        if (value.timestamp) {
            try {
                if (value.text.matches("[0-9]{9,10}")) result = Math.multiplyExact(Long.parseLong(value.text), 1000L);
                else if (value.text.matches("[0-9]{13}")) result = Long.parseLong(value.text);
            } catch (NumberFormatException | ArithmeticException ignored) {
                // Report a stable public error below; do not include account data.
            }
        } else {
            for (DateTimeFormatter format : LOCAL_TIME_FORMATS) {
                try {
                    result = LocalDateTime.parse(value.text, format).atZone(WECHAT_LOCAL_ZONE).toInstant().toEpochMilli();
                    break;
                } catch (java.time.DateTimeException ignored) {
                    // A numeric value without is_timestamp=true is not silently guessed.
                }
            }
        }
        if (result < Transaction.MIN_OCCURRED_AT || result >= Transaction.MAX_OCCURRED_AT) {
            throw problem("INVALID_TIME", "交易时间格式、标记或范围不符合要求");
        }
        return result;
    }

    private static long amount(String text) throws ParseProblem {
        if (!text.matches("[+-](?:0|[1-9][0-9]{0,11})(?:\\.[0-9]{1,2})?")) {
            throw problem("INVALID_AMOUNT", "金额需带明确正负号，且最多保留两位小数");
        }
        try {
            long cents = new BigDecimal(text).movePointRight(2).longValueExact();
            if (cents == 0) throw problem("ZERO_AMOUNT", "零金额记录暂不计入账本");
            if (cents > Transaction.MAX_ABS_AMOUNT_MINOR || cents < -Transaction.MAX_ABS_AMOUNT_MINOR) {
                throw problem("INVALID_AMOUNT", "交易金额超出支持范围");
            }
            return cents;
        } catch (ArithmeticException e) {
            throw problem("INVALID_AMOUNT", "交易金额无法精确转换为分");
        }
    }

    private static String requiredString(JSONObject object, String key, String message) throws ParseProblem {
        Object value = object.opt(key);
        if (!(value instanceof String) || ((String) value).trim().isEmpty()) {
            throw problem("INVALID_FIELD", message);
        }
        return ((String) value).trim();
    }

    private static boolean isZeroInteger(Object value) {
        return (value instanceof Integer || value instanceof Long) && ((Number) value).longValue() == 0;
    }

    private static ParseProblem problem(String code, String message) {
        return new ParseProblem(code, message);
    }

    private static final class FieldValue {
        final String text;
        final boolean timestamp;
        FieldValue(String text, boolean timestamp) {
            this.text = text;
            this.timestamp = timestamp;
        }
    }

    private static final class ParseProblem extends Exception {
        final String code;
        ParseProblem(String code, String message) {
            super(message);
            this.code = code;
        }
    }
}
