package dev.tallybook.core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Minimal local record. Money is signed CNY cents; time is Unix milliseconds. */
public final class Transaction {
    public static final String PROVIDER = "wechat";
    public static final String MANUAL_PROVIDER = "manual";
    public static final String MANUAL_STATUS = "手动记录";
    public static final String QUERY_PROVIDER = "wechat_query";
    public static final String QUERY_STATUS = "接口查询待核对";
    public static final String QUERY_CONFIRMED_STATUS = "已核对";
    public static final String QUERY_REVIEW_REASON = "请对照微信核对金额、收支方向和重复记录；退款、转账、充值、提现及理财需确认是否应计入收支";
    private static final String QUERY_DESCRIPTION_PREFIX = "微信账单列表接口查询；微信状态：";
    public static final int SCHEMA_VERSION = 1;
    public static final long MIN_OCCURRED_AT = 946684800000L; // 2000-01-01 UTC
    public static final long MAX_OCCURRED_AT = 4102444800000L; // 2100-01-01 UTC, exclusive
    public static final long MAX_ABS_AMOUNT_MINOR = 99_999_999_999_999L;

    private static final Set<String> JSON_KEYS = new HashSet<>(Arrays.asList(
            "schemaVersion", "provider", "id", "tradeId", "counterparty", "status",
            "paymentMethod", "description", "amountMinor", "occurredAt", "reviewRequired", "reviewReason"));
    private static final Set<String> CONFIRMED_STATUSES = new HashSet<>(Arrays.asList(
            "支付成功", "交易成功", "已支付", "收款成功", "已收款"));

    public final String provider;
    public final String id;
    public final String tradeId;
    /** Original list bill_id; present only for the query provider, never a fabricated trade number. */
    public final String queryBillId;
    public final String counterparty;
    public final String status;
    public final String paymentMethod;
    public final String description;
    public final long amountMinor;
    public final long occurredAt;
    public final boolean reviewRequired;
    public final String reviewReason;

    /** The deterministic id is calculated here, never supplied by the caller. */
    public Transaction(String tradeId, String counterparty, String status, String paymentMethod,
                       String description, long amountMinor, long occurredAt,
                       boolean reviewRequired, String reviewReason) {
        this(PROVIDER, tradeId, counterparty, status, paymentMethod, description, amountMinor,
                occurredAt, reviewRequired, reviewReason);
    }

    /** A deliberate user entry; entryId is stable across edits and is normally a UUID. */
    public static Transaction manual(String entryId, String title, String category, String note,
                                     long signedAmount, long occurredAt) {
        return new Transaction(MANUAL_PROVIDER, entryId, title, MANUAL_STATUS, category, note,
                signedAmount, occurredAt, false, "");
    }

    public static Transaction query(String billId, String transId, String title, String wechatState,
                                    long amountMinor, long occurredAt) {
        String originalState = checkedText(wechatState, 256, true, "wechatState");
        return new Transaction(QUERY_PROVIDER, transId, title, QUERY_STATUS, "",
                QUERY_DESCRIPTION_PREFIX + originalState, amountMinor, occurredAt, true,
                QUERY_REVIEW_REASON, billId);
    }

    public Transaction confirmQuery() {
        if (!QUERY_PROVIDER.equals(provider)) throw new IllegalArgumentException("Only query records can be confirmed");
        return new Transaction(provider, tradeId, counterparty, QUERY_CONFIRMED_STATUS,
                paymentMethod, description, amountMinor, occurredAt, false, "", queryBillId);
    }

    private Transaction(String provider, String tradeId, String counterparty, String status,
                        String paymentMethod, String description, long amountMinor, long occurredAt,
                        boolean reviewRequired, String reviewReason) {
        this(provider, tradeId, counterparty, status, paymentMethod, description, amountMinor,
                occurredAt, reviewRequired, reviewReason, "");
    }

    private Transaction(String provider, String tradeId, String counterparty, String status,
                        String paymentMethod, String description, long amountMinor, long occurredAt,
                        boolean reviewRequired, String reviewReason, String queryBillId) {
        if (!PROVIDER.equals(provider) && !MANUAL_PROVIDER.equals(provider) && !QUERY_PROVIDER.equals(provider)) {
            throw new IllegalArgumentException("Unsupported transaction provider");
        }
        this.provider = provider;
        boolean manual = MANUAL_PROVIDER.equals(provider);
        boolean query = QUERY_PROVIDER.equals(provider);
        this.queryBillId = checkedText(queryBillId, 512, !query, "queryBillId");
        if (!query && !this.queryBillId.isEmpty()) throw new IllegalArgumentException("Unexpected query identity");
        this.tradeId = checkedText(tradeId, query ? 512 : 128, !manual, "tradeId");
        if (!query && !this.tradeId.isEmpty() && !this.tradeId.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new IllegalArgumentException("Invalid tradeId");
        }
        this.counterparty = checkedText(counterparty, 1024, false, "counterparty");
        this.status = checkedText(status, 256, true, "status");
        this.paymentMethod = checkedText(paymentMethod, 512, !manual, "paymentMethod");
        this.description = checkedText(description, 2048, true, "description");
        if (amountMinor == 0 || amountMinor > MAX_ABS_AMOUNT_MINOR || amountMinor < -MAX_ABS_AMOUNT_MINOR) {
            throw new IllegalArgumentException("Invalid nonzero CNY amount");
        }
        if (occurredAt < MIN_OCCURRED_AT || occurredAt >= MAX_OCCURRED_AT) {
            throw new IllegalArgumentException("Invalid transaction time");
        }
        this.amountMinor = amountMinor;
        this.occurredAt = occurredAt;
        this.reviewRequired = reviewRequired;
        this.reviewReason = checkedText(reviewReason, 2048, !reviewRequired, "reviewReason");
        if (!reviewRequired && !this.reviewReason.isEmpty()) {
            throw new IllegalArgumentException("Review reason without review flag");
        }
        if (manual && (!MANUAL_STATUS.equals(this.status) || reviewRequired)) {
            throw new IllegalArgumentException("Invalid manual transaction status");
        }
        if (query && (!this.paymentMethod.isEmpty() || occurredAt % 1000 != 0
                || !this.description.startsWith(QUERY_DESCRIPTION_PREFIX)
                || this.description.length() > QUERY_DESCRIPTION_PREFIX.length() + 256
                || (reviewRequired ? !QUERY_STATUS.equals(this.status) || !QUERY_REVIEW_REASON.equals(this.reviewReason)
                                   : !QUERY_CONFIRMED_STATUS.equals(this.status)))) {
            throw new IllegalArgumentException("Invalid query transaction status");
        }
        if (!manual && !query && !reviewRequired && (this.tradeId.isEmpty()
                || !isConfirmedStatus(this.status) || isTransferLike(this.counterparty, this.description))) {
            throw new IllegalArgumentException("Transaction requires review");
        }
        this.id = query ? digestId(provider, new JSONArray().put(provider).put(this.queryBillId)
                .put(this.tradeId).put(occurredAt)) : calculateId(provider, this.tradeId, this.counterparty,
                this.paymentMethod, this.description, amountMinor, occurredAt);
    }

    public String toJson() {
        try {
            JSONObject json = new JSONObject();
            json.put("schemaVersion", SCHEMA_VERSION);
            json.put("provider", provider);
            json.put("id", id);
            json.put("tradeId", tradeId);
            if (QUERY_PROVIDER.equals(provider)) json.put("queryBillId", queryBillId);
            json.put("counterparty", counterparty);
            json.put("status", status);
            json.put("paymentMethod", paymentMethod);
            json.put("description", description);
            json.put("amountMinor", amountMinor);
            json.put("occurredAt", occurredAt);
            json.put("reviewRequired", reviewRequired);
            json.put("reviewReason", reviewReason);
            return json.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot serialize transaction", e);
        }
    }

    /** Strict normalized record reader, for app storage/IPC; not a WeChat payload reader. */
    public static Transaction fromJson(String source) {
        try {
            if (source == null || source.getBytes(StandardCharsets.UTF_8).length > WechatBillParser.MAX_INPUT_BYTES) {
                throw new IllegalArgumentException("Invalid record length");
            }
            JSONObject json = JsonInput.object(source);
            Set<String> keys = new HashSet<>();
            json.keys().forEachRemaining(keys::add);
            boolean query = QUERY_PROVIDER.equals(string(json, "provider"));
            Set<String> expectedKeys = new HashSet<>(JSON_KEYS);
            if (query) expectedKeys.add("queryBillId");
            if (!keys.equals(expectedKeys) || integer(json, "schemaVersion") != SCHEMA_VERSION) {
                throw new IllegalArgumentException("Unsupported record schema/provider");
            }
            Object flag = json.get("reviewRequired");
            if (!(flag instanceof Boolean)) throw new IllegalArgumentException("Invalid review flag");
            Transaction transaction = new Transaction(string(json, "provider"),
                    string(json, "tradeId"), string(json, "counterparty"),
                    string(json, "status"), string(json, "paymentMethod"), string(json, "description"),
                    integer(json, "amountMinor"), integer(json, "occurredAt"),
                    (Boolean) flag, string(json, "reviewReason"), query ? string(json, "queryBillId") : "");
            if (!transaction.id.equals(string(json, "id"))) {
                throw new IllegalArgumentException("Record id does not match transaction");
            }
            return transaction;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid transaction record", e);
        }
    }

    static boolean isConfirmedStatus(String status) {
        return CONFIRMED_STATUSES.contains(status);
    }

    static boolean isTransferLike(String counterparty, String description) {
        String text = counterparty + " " + description;
        return text.contains("转账") || text.contains("充值") || text.contains("提现")
                || text.contains("还款") || text.contains("零钱通") || text.contains("理财");
    }

    private static String calculateId(String provider, String tradeId, String counterparty, String paymentMethod,
                                      String description, long amountMinor, long occurredAt) {
        JSONArray identity = new JSONArray().put(provider);
        if (!tradeId.isEmpty()) {
            identity.put("tradeId").put(tradeId);
        } else {
            identity.put("fingerprint").put(counterparty).put(paymentMethod).put(description)
                    .put(amountMinor).put(occurredAt);
        }
        return digestId(provider, identity);
    }

    private static String digestId(String provider, JSONArray identity) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(identity.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(provider).append(':');
            for (byte value : digest) hex.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static String checkedText(String text, int maxLength, boolean allowEmpty, String field) {
        if (text == null || text.length() > maxLength || (!allowEmpty && text.trim().isEmpty())
                || !text.equals(text.trim())) {
            throw new IllegalArgumentException("Invalid " + field);
        }
        for (int i = 0; i < text.length(); i++) {
            if (Character.isISOControl(text.charAt(i))) throw new IllegalArgumentException("Invalid " + field);
        }
        return text;
    }

    private static String string(JSONObject json, String key) throws Exception {
        Object value = json.get(key);
        if (!(value instanceof String)) throw new IllegalArgumentException("Invalid " + key);
        return (String) value;
    }

    private static long integer(JSONObject json, String key) throws Exception {
        Object value = json.get(key);
        if (!(value instanceof Integer) && !(value instanceof Long)) {
            throw new IllegalArgumentException("Invalid integer " + key);
        }
        return ((Number) value).longValue();
    }
}
