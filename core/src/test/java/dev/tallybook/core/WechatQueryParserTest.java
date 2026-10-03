package dev.tallybook.core;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.*;

public class WechatQueryParserTest {
    private JSONObject row(String id) throws Exception {
        return new JSONObject().put("bill_id", id).put("trans_id", "fiction-transaction")
                .put("title", "虚构午餐").put("timestamp", 1_760_000_000L).put("fee", 1234)
                .put("fee_type", "CNY").put("fee_attr", "negtive").put("current_state", "支付成功");
    }
    private WechatQueryParser.Result parse(JSONObject... rows) throws Exception {
        JSONArray array = new JSONArray();
        for (JSONObject row : rows) array.put(row);
        return WechatQueryParser.parse(new JSONObject().put("records", array).toString());
    }

    @Test public void allCandidatesNeedReviewAndRoundTrip() throws Exception {
        Transaction transaction = parse(row("fiction-bill")).transactions.get(0);
        assertEquals(-1234, transaction.amountMinor);
        assertEquals(1_760_000_000_000L, transaction.occurredAt);
        assertEquals("fiction-bill", transaction.queryBillId);
        assertEquals("fiction-transaction", transaction.tradeId);
        assertTrue(transaction.reviewRequired);
        assertEquals(Transaction.QUERY_PROVIDER, transaction.provider);
        assertEquals(transaction.toJson(), Transaction.fromJson(transaction.toJson()).toJson());
        Transaction confirmed = transaction.confirmQuery();
        assertEquals(transaction.id, confirmed.id);
        assertFalse(confirmed.reviewRequired);
        assertEquals(Transaction.QUERY_CONFIRMED_STATUS, confirmed.status);
        assertEquals(confirmed.toJson(), Transaction.fromJson(confirmed.toJson()).toJson());
    }

    @Test public void usesCompositeIdentityWithoutTruncatingOpaqueIdentifiers() throws Exception {
        String longId = "bill-" + "a".repeat(400);
        JSONObject first = row(longId);
        JSONObject second = row(longId).put("trans_id", "other-transaction");
        JSONObject third = row(longId).put("timestamp", 1_760_000_001L);
        WechatQueryParser.Result result = parse(first, second, third, row(longId).put("title", "虚构修改名称"));
        assertEquals(3, result.transactions.size());
        assertEquals(1, result.duplicateRows);
        assertEquals(longId, result.transactions.get(0).queryBillId);
    }

    @Test public void neutralAndUnsupportedNeverGuessMoney() throws Exception {
        WechatQueryParser.Result result = parse(row("neutral").put("fee_attr", "neutral").put("fee", 0),
                row("foreign").put("fee_type", "USD"), row("unknown").put("fee_attr", "negative"),
                row("income").put("fee_attr", "positive"));
        assertEquals(4, result.totalRows);
        assertEquals(1, result.neutralRows);
        assertEquals(2, result.unsupportedRows);
        assertEquals(3, result.skippedRows);
        assertEquals(1234, result.transactions.get(0).amountMinor);
    }

    @Test public void amountsRequireBoundedNonzeroIntegerMinorUnits() throws Exception {
        WechatQueryParser.Result result = parse(row("zero").put("fee", 0), row("negative").put("fee", -1),
                row("string").put("fee", "1234"), row("fraction").put("fee", 1.25),
                row("overflow").put("fee", Long.MAX_VALUE),
                row("limit").put("fee", Transaction.MAX_ABS_AMOUNT_MINOR));
        assertEquals(5, result.invalidRows);
        assertEquals(1, result.transactions.size());
    }

    @Test public void timesAreSecondsAndBoundedBeforeMultiplication() throws Exception {
        WechatQueryParser.Result result = parse(row("early").put("timestamp", Transaction.MIN_OCCURRED_AT / 1000 - 1),
                row("late").put("timestamp", Transaction.MAX_OCCURRED_AT / 1000),
                row("overflow").put("timestamp", Long.MAX_VALUE),
                row("millis").put("timestamp", 1_760_000_000_000L),
                row("first").put("timestamp", Transaction.MIN_OCCURRED_AT / 1000));
        assertEquals(4, result.invalidRows);
        assertEquals(Transaction.MIN_OCCURRED_AT, result.transactions.get(0).occurredAt);
    }

    @Test public void unknownCredentialFieldAndInvalidTitleAreSkipped() throws Exception {
        WechatQueryParser.Result result = parse(row("token").put("exportkey", "FICTION"),
                row("title").put("title", ""), row("control").put("title", "fake\nname"));
        assertEquals(3, result.invalidRows);
        assertTrue(result.transactions.isEmpty());
    }

    @Test public void malformedDeepAndDuplicateJsonAreRejected() {
        String[] inputs = {"{}", "{\"records\":[],\"records\":[]}", "{records:[]}",
                "{\"records\":[]}" + "x", "{\"records\":" + "[".repeat(40) + "]".repeat(40) + "}"};
        for (String input : inputs) {
            assertThrows(IllegalArgumentException.class, () -> WechatQueryParser.parse(input));
        }
    }

    @Test public void tooManyRowsAndOversizedFilesAreRejected() throws Exception {
        JSONArray rows = new JSONArray();
        for (int i = 0; i <= 200; i++) rows.put(row("fiction-" + i));
        assertThrows(IllegalArgumentException.class,
                () -> WechatQueryParser.parse(new JSONObject().put("records", rows).toString()));
        assertThrows(IllegalArgumentException.class,
                () -> WechatQueryParser.decodeDocument(" ".repeat(WechatQueryParser.MAX_INPUT_BYTES + 1)));
    }

    @Test public void onlyQueryRecordsCanUseQueryConfirmation() {
        Transaction manual = Transaction.manual("fiction-manual", "虚构餐饮", "餐饮", "", -1,
                1_760_000_000_000L);
        assertThrows(IllegalArgumentException.class, manual::confirmQuery);
    }

    @Test public void jsonCannotMixStatusesProvidersOrForgeIdentity() throws Exception {
        Transaction transaction = parse(row("fiction-bill")).transactions.get(0);
        for (JSONObject invalid : new JSONObject[]{
                new JSONObject(transaction.toJson()).put("status", "支付成功"),
                new JSONObject(transaction.toJson()).put("reviewRequired", false),
                new JSONObject(transaction.toJson()).put("reviewReason", "不同原因"),
                new JSONObject(transaction.toJson()).put("provider", Transaction.PROVIDER),
                new JSONObject(transaction.toJson()).put("queryBillId", "different-bill"),
                new JSONObject(transaction.toJson()).put("paymentMethod", "伪造渠道")}) {
            assertThrows(IllegalArgumentException.class, () -> Transaction.fromJson(invalid.toString()));
        }
    }
}
