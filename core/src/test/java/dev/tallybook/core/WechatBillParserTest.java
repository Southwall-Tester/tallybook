package dev.tallybook.core;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.junit.Assert.*;

public class WechatBillParserTest {
    private static final long SYNTHETIC_TIME = LocalDateTime.of(2026, 10, 3, 8, 20)
            .atZone(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();

    @Test public void parsesExpenseInExactSignedCents() throws Exception {
        Transaction transaction = success(fixture("expense-detail.json"));
        assertEquals(-1280, transaction.amountMinor);
        assertEquals(SYNTHETIC_TIME, transaction.occurredAt);
        assertEquals("DEMO-WECHAT-20261003-001", transaction.tradeId);
        assertEquals("演示·晨光早餐铺", transaction.counterparty);
        assertFalse(transaction.reviewRequired);
        assertEquals("", transaction.reviewReason);
    }

    @Test public void parsesIncomeWithoutGuessingDirection() throws Exception {
        Transaction transaction = success(fixture("income-detail.json"));
        assertEquals(12000, transaction.amountMinor);
        assertFalse(transaction.reviewRequired);
    }

    @Test public void understandsNestedEscapingInBridge() throws Exception {
        Transaction transaction = success(fixture("escaped-bridge.txt"));
        assertEquals("演示·\"引号\"\\小店", transaction.counterparty);
        assertEquals(-1280, transaction.amountMinor);
    }

    @Test public void acceptsBothBridgeJsonAndJavascriptEnvelope() throws Exception {
        String detail = fixture("expense-detail.json");
        JSONObject bridge = bridge(detail);
        String firstId = success(bridge.toString()).id;
        assertEquals(firstId, success("javascript:WeixinJSBridge._handleMessageFromWeixin(" + bridge + ");").id);
        assertEquals(firstId, success("WeixinJSBridge._handleMessageFromWeixin(" + bridge + ")").id);
    }

    @Test public void sameTransactionRemainsStableAcrossWrappersAndUnusedFields() throws Exception {
        JSONObject detail = detail();
        String originalId = success(detail.toString()).id;
        detail.put("entrances", new JSONArray().put(new JSONObject().put("unused", "不保留这个字段")));
        detail.put("service_module", new JSONObject().put("placeholder", "虚构内容"));
        assertEquals(originalId, success(bridge(detail.toString()).toString()).id);
        Transaction normalized = success(detail.toString());
        assertFalse(normalized.toJson().contains("entrances"));
        assertFalse(normalized.toJson().contains("placeholder"));
    }

    @Test public void sameAmountAndTimeDifferentOrderIdsDoNotMerge() throws Exception {
        JSONObject one = detail();
        JSONObject two = detail();
        set(two, "交易单号", "DEMO-DIFFERENT-ORDER", false);
        assertNotEquals(success(one.toString()).id, success(two.toString()).id);
    }

    @Test public void statusChangesKeepIdentityButRefundRequiresReview() throws Exception {
        JSONObject original = detail();
        String originalId = success(original.toString()).id;
        set(original, "当前状态", "已全额退款", false);
        Transaction refunded = success(original.toString());
        assertEquals(originalId, refunded.id);
        assertTrue(refunded.reviewRequired);
        assertTrue(refunded.reviewReason.contains("已全额退款"));
    }

    @Test public void refundFailedPendingAndUnknownStatusesRequireReview() throws Exception {
        for (String status : new String[]{"已全额退款", "退款处理中", "支付失败", "等待付款", "未知状态"}) {
            JSONObject detail = detail();
            set(detail, "当前状态", status, false);
            Transaction transaction = success(detail.toString());
            assertTrue(status, transaction.reviewRequired);
            assertFalse(transaction.reviewReason.isEmpty());
        }
        assertTrue(success(fixture("refund-detail.json")).reviewRequired);
    }

    @Test public void absentStatusRequiresReview() throws Exception {
        JSONObject detail = detail();
        remove(detail, "当前状态");
        assertTrue(success(detail.toString()).reviewRequired);
    }

    @Test public void absentTradeIdGetsDeterministicReviewedFingerprint() throws Exception {
        JSONObject detail = detail();
        remove(detail, "交易单号");
        Transaction first = success(detail.toString());
        Transaction second = success(bridge(detail.toString()).toString());
        assertTrue(first.reviewRequired);
        assertEquals("", first.tradeId);
        assertEquals(first.id, second.id);
        assertTrue(first.reviewReason.contains("指纹"));
    }

    @Test public void transfersNeverSilentlyBecomeConfirmedIncomeOrExpense() throws Exception {
        JSONObject detail = detail();
        detail.getJSONObject("header").put("nickname", "演示·转账给朋友");
        assertTrue(success(detail.toString()).reviewRequired);
    }

    @Test public void zeroAmountsAreRejected() throws Exception {
        for (String amount : new String[]{"+0.00", "-0.00", "+0", "-0.0"}) {
            JSONObject detail = detail();
            detail.getJSONObject("header").put("fee", amount);
            failure(detail.toString(), "ZERO_AMOUNT");
        }
    }

    @Test public void unsignedInvalidOverpreciseAndHugeAmountsAreRejected() throws Exception {
        for (String amount : new String[]{"12.80", "￥12.80", "-12.345", "-1e2", "NaN", "--1.00",
                "-01.00", "-1,000.00", "-9999999999999.00"}) {
            JSONObject detail = detail();
            detail.getJSONObject("header").put("fee", amount);
            failure(detail.toString(), "INVALID_AMOUNT");
        }
    }

    @Test public void fractionalAmountsDoNotLoseCents() throws Exception {
        JSONObject detail = detail();
        for (String amount : new String[]{"-0.01", "-0.29", "-1.1", "+999999999999.99"}) {
            detail.getJSONObject("header").put("fee", amount);
            assertEquals(new java.math.BigDecimal(amount).movePointRight(2).longValueExact(),
                    success(detail.toString()).amountMinor);
        }
    }

    @Test public void missingTimeNeverFallsBackToCollectionTime() throws Exception {
        JSONObject detail = detail();
        remove(detail, "支付时间");
        failure(detail.toString(), "MISSING_TIME");
    }

    @Test public void unixSecondsAndMillisecondsRequireTimestampMarker() throws Exception {
        JSONObject detail = detail();
        set(detail, "支付时间", String.valueOf(SYNTHETIC_TIME / 1000), true);
        assertEquals(SYNTHETIC_TIME, success(detail.toString()).occurredAt);
        set(detail, "支付时间", String.valueOf(SYNTHETIC_TIME), true);
        assertEquals(SYNTHETIC_TIME, success(detail.toString()).occurredAt);
        set(detail, "支付时间", String.valueOf(SYNTHETIC_TIME), false);
        failure(detail.toString(), "INVALID_TIME");
    }

    @Test public void invalidDatesAndTimeRangesAreRejected() throws Exception {
        for (String value : new String[]{"2026-02-30 12:00:00", "2026-10-03", "10/03/26 08:20", "1999-12-31 23:59:59", "2101-01-01 00:00:00"}) {
            JSONObject detail = detail();
            set(detail, "支付时间", value, false);
            failure(detail.toString(), "INVALID_TIME");
        }
        JSONObject detail = detail();
        set(detail, "支付时间", "177000000000", true);
        failure(detail.toString(), "INVALID_TIME");
    }

    @Test public void conflictingTimesAreRejected() throws Exception {
        JSONObject detail = detail();
        detail.getJSONArray("preview").put(row("交易时间", "2026-10-03 08:21:00", false));
        failure(detail.toString(), "AMBIGUOUS_TIME");
    }

    @Test public void conflictingTradeIdsAreRejected() throws Exception {
        JSONObject detail = detail();
        detail.getJSONArray("preview").put(row("微信支付订单号", "DEMO-CONFLICT", false));
        failure(detail.toString(), "AMBIGUOUS_FIELD");
    }

    @Test public void nonSuccessBridgeOrDetailIsRejected() throws Exception {
        JSONObject bridge = bridge(fixture("expense-detail.json"));
        bridge.getJSONObject("__json_message").getJSONObject("__params").put("err_msg", "nativeWXPayCgiTunnel:fail");
        failure(bridge.toString(), "REMOTE_ERROR");
        for (Object code : new Object[]{1, -1, "0", 0.5, JSONObject.NULL}) {
            JSONObject detail = detail().put("ret_code", code);
            failure(detail.toString(), "REMOTE_ERROR");
        }
    }

    @Test public void unknownSchemaAndListsAreRejected() throws Exception {
        failure("{\"rows\":[{\"amount\":12.8}]}", "UNSUPPORTED_SCHEMA");
        failure("[" + fixture("expense-detail.json") + "]", "UNSUPPORTED_SCHEMA");
        failure("{\"header\":{\"nickname\":\"演示\",\"fee\":\"-1.00\"},\"preview\":[]}", "UNSUPPORTED_SCHEMA");
        failure("{\"__json_message\":{\"__params\":{\"err_msg\":\"nativeWXPayCgiTunnel:ok\",\"respbuf\":{}}}}", "UNSUPPORTED_SCHEMA");
    }

    @Test public void malformedAndTrailingExecutableDataIsRejected() throws Exception {
        failure("{", "INVALID_JSON");
        failure(fixture("expense-detail.json") + ";alert('never execute')", "INVALID_JSON");
        failure("javascript:WeixinJSBridge._handleMessageFromWeixin({}", "INVALID_WRAPPER");
        failure("javascript:alert('never execute')", "UNSUPPORTED_SCHEMA");
        failure(null, "EMPTY_INPUT");
        failure("  ", "EMPTY_INPUT");
    }

    @Test public void deepPayloadAndUtf8ByteOversizeAreRejected() throws Exception {
        StringBuilder deep = new StringBuilder("{\"x\":");
        for (int i = 0; i < 40; i++) deep.append('[');
        deep.append('0');
        for (int i = 0; i < 40; i++) deep.append(']');
        deep.append('}');
        failure(deep.toString(), "INVALID_JSON");
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 90000; i++) big.append('账');
        failure(big.toString(), "INPUT_TOO_LARGE");
    }

    @Test public void normalizedRecordRoundTripIsStrict() throws Exception {
        Transaction original = success(fixture("expense-detail.json"));
        Transaction restored = Transaction.fromJson(original.toJson());
        assertEquals(original.id, restored.id);
        assertEquals(original.amountMinor, restored.amountMinor);
        assertEquals("wechat", restored.provider);
        JSONObject valid = new JSONObject(original.toJson());
        invalidRecord(new JSONObject(valid.toString()).put("provider", "other"));
        invalidRecord(new JSONObject(valid.toString()).put("schemaVersion", 2));
        invalidRecord(new JSONObject(valid.toString()).put("amountMinor", "-1280"));
        invalidRecord(new JSONObject(valid.toString()).put("occurredAt", 1));
        invalidRecord(new JSONObject(valid.toString()).put("reviewRequired", "false"));
        invalidRecord(new JSONObject(valid.toString()).put("id", "forged"));
        invalidRecord(new JSONObject(valid.toString()).put("account_token", "must-not-pass"));
        JSONObject missing = new JSONObject(valid.toString());
        missing.remove("paymentMethod");
        invalidRecord(missing);
    }

    @Test public void normalizedRefundCannotClearReviewFlag() throws Exception {
        Transaction refund = success(fixture("refund-detail.json"));
        JSONObject json = new JSONObject(refund.toJson()).put("reviewRequired", false).put("reviewReason", "");
        invalidRecord(json);
        assertTrue(Transaction.fromJson(refund.toJson()).reviewRequired);
    }

    @Test public void bothReadersRejectDeepDataBeforePlatformJsonRecursion() throws Exception {
        StringBuilder input = new StringBuilder("{\"unexpected\":");
        for (int i = 0; i < 8000; i++) input.append('[');
        input.append('0');
        for (int i = 0; i < 8000; i++) input.append(']');
        input.append('}');
        failure(input.toString(), "INVALID_JSON");
        invalidRecordText(input.toString());
    }

    @Test public void strictJsonRejectsPlatformLeniencyAndDuplicateEscapedKeys() throws Exception {
        for (String input : new String[]{"{'header':{}}", "{unquoted:true}", "{\"x\":NaN}",
                "{\"x\":1,}", "{\"x\":[1,]}", "{\"x\":01}", "{\"x\":1;\"y\":2}",
                "{\"x\":1,\"x\":2}", "{\"x\":1,\"\\u0078\":2}", "{\"x\":/*comment*/1}"}) {
            failure(input, "INVALID_JSON");
            invalidRecordText(input);
        }
    }

    @Test public void everyBundledDemoIsExplicitlyFictionalAndParseable() throws Exception {
        for (String name : new String[]{"demo-wechat.json", "demo-wechat-2.json", "demo-wechat-3.json", "demo-wechat-4.json"}) {
            String source = fixture(name);
            assertTrue(source.contains("全部为虚构演示数据"));
            assertTrue(success(source).tradeId.startsWith("DEMO-"));
        }
    }

    private static Transaction success(String input) {
        ParseResult result = WechatBillParser.parse(input);
        assertTrue(result.code + ": " + result.message, result.isSuccess());
        assertNotNull(result.transaction);
        return result.transaction;
    }

    private static void failure(String input, String expectedCode) {
        ParseResult result = WechatBillParser.parse(input);
        assertFalse(result.message, result.isSuccess());
        assertNull(result.transaction);
        assertEquals(expectedCode, result.code);
    }

    private static JSONObject detail() throws Exception {
        return new JSONObject(fixture("expense-detail.json"));
    }

    private static JSONObject bridge(String detail) throws Exception {
        return new JSONObject().put("__json_message", new JSONObject().put("__params",
                new JSONObject().put("err_msg", "nativeWXPayCgiTunnel:ok").put("respbuf", detail)));
    }

    private static JSONObject row(String label, String value, boolean timestamp) throws Exception {
        JSONObject valueObject = new JSONObject().put("name", value);
        if (timestamp) valueObject.put("is_timestamp", true);
        return new JSONObject().put("label", new JSONObject().put("name", label))
                .put("value", new JSONArray().put(valueObject));
    }

    private static void set(JSONObject detail, String label, String value, boolean timestamp) throws Exception {
        remove(detail, label);
        detail.getJSONArray("preview").put(row(label, value, timestamp));
    }

    private static void remove(JSONObject detail, String label) throws Exception {
        JSONArray original = detail.getJSONArray("preview");
        JSONArray replacement = new JSONArray();
        for (int i = 0; i < original.length(); i++) {
            JSONObject row = original.getJSONObject(i);
            if (!row.has("label") || !label.equals(row.getJSONObject("label").optString("name"))) replacement.put(row);
        }
        detail.put("preview", replacement);
    }

    private static String fixture(String name) throws Exception {
        InputStream stream = WechatBillParserTest.class.getResourceAsStream("/" + name);
        assertNotNull("Missing fixture " + name, stream);
        try (InputStream input = stream) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void invalidRecord(JSONObject json) {
        invalidRecordText(json.toString());
    }

    private static void invalidRecordText(String json) {
        try {
            Transaction.fromJson(json);
            fail("Record should have been rejected");
        } catch (IllegalArgumentException expected) {
            // Strict normalization is a boundary for storage and IPC.
        }
    }
}
