package dev.tallybook.core;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.*;

public class ManualTransactionTest {
    private static final long TIME = 1_791_000_000_000L;

    @Test public void manualRecordRoundTripsWithoutLosingFields() {
        Transaction original = Transaction.manual("ENTRY-001", "午餐", "餐饮", "食堂", -1250, TIME);
        Transaction restored = Transaction.fromJson(original.toJson());
        assertEquals(Transaction.MANUAL_PROVIDER, restored.provider);
        assertEquals(Transaction.MANUAL_STATUS, restored.status);
        assertTrue(restored.id.startsWith("manual:"));
        assertEquals(original.id, restored.id);
        assertEquals("ENTRY-001", restored.tradeId);
        assertEquals("午餐", restored.counterparty);
        assertEquals("餐饮", restored.paymentMethod);
        assertEquals("食堂", restored.description);
        assertEquals(-1250, restored.amountMinor);
        assertEquals(TIME, restored.occurredAt);
        assertFalse(restored.reviewRequired);
        assertEquals("", restored.reviewReason);
    }

    @Test public void deliberateManualEntryDoesNotUseWechatTransferGuessing() {
        Transaction transaction = Transaction.manual("ENTRY-002", "家人转账生活费", "生活费", "转账", 200000, TIME);
        assertFalse(Transaction.fromJson(transaction.toJson()).reviewRequired);
        assertEquals(200000, transaction.amountMinor);
        assertThrows(IllegalArgumentException.class, () -> new Transaction(
                "ENTRY-002", "家人转账生活费", "支付成功", "零钱", "转账", 200000, TIME, false, ""));
    }

    @Test public void entryIdentitySurvivesEditsAndHasSeparateProviderNamespace() {
        Transaction first = Transaction.manual("ORDER-001", "午餐", "餐饮", "", -1250, TIME);
        Transaction edit = Transaction.manual("ORDER-001", "晚餐", "其他", "已修正", -2600, TIME + 1000);
        Transaction wechat = new Transaction("ORDER-001", "午餐", "支付成功", "零钱", "", -1250, TIME, false, "");
        assertEquals(first.id, edit.id);
        assertNotEquals(first.id, wechat.id);
        assertEquals("wechat:8611eb25e384e43e25ff5620d89ef95d4d209a6c92a5c6d29c028861c9392145", wechat.id);
        assertEquals(wechat.id, Transaction.fromJson(wechat.toJson()).id);
    }

    @Test public void manualEntryRequiresExplicitValidIdentityAndFields() {
        for (String badId : new String[]{"", " with-space", "invalid/id", "x".repeat(129)}) {
            assertThrows(IllegalArgumentException.class,
                    () -> Transaction.manual(badId, "午餐", "餐饮", "", -1, TIME));
        }
        assertThrows(IllegalArgumentException.class, () -> Transaction.manual("a", "", "餐饮", "", -1, TIME));
        assertThrows(IllegalArgumentException.class, () -> Transaction.manual("a", "午餐", "", "", -1, TIME));
        assertThrows(IllegalArgumentException.class, () -> Transaction.manual("a", "午餐 ", "餐饮", "", -1, TIME));
        assertThrows(IllegalArgumentException.class, () -> Transaction.manual("a", "午餐", "餐饮", "x\ny", -1, TIME));
        assertThrows(IllegalArgumentException.class, () -> Transaction.manual("a", "午餐", "餐饮", null, -1, TIME));
    }

    @Test public void manualAmountAndTimestampBoundariesAreChecked() {
        for (long invalid : new long[]{0, Long.MIN_VALUE, Long.MAX_VALUE,
                Transaction.MAX_ABS_AMOUNT_MINOR + 1, -Transaction.MAX_ABS_AMOUNT_MINOR - 1}) {
            assertThrows(IllegalArgumentException.class, () -> Transaction.manual("a", "收入", "其他", "", invalid, TIME));
        }
        Transaction.manual("a", "收入", "其他", "", Transaction.MAX_ABS_AMOUNT_MINOR, Transaction.MIN_OCCURRED_AT);
        Transaction.manual("a", "支出", "其他", "", -Transaction.MAX_ABS_AMOUNT_MINOR, Transaction.MAX_OCCURRED_AT - 1);
        assertThrows(IllegalArgumentException.class, () -> Transaction.manual("a", "收入", "其他", "", 1, Transaction.MIN_OCCURRED_AT - 1));
        assertThrows(IllegalArgumentException.class, () -> Transaction.manual("a", "收入", "其他", "", 1, Transaction.MAX_OCCURRED_AT));
    }

    @Test public void normalizedManualRecordCannotForgeStatusReviewOrProvider() throws Exception {
        JSONObject record = new JSONObject(Transaction.manual("a", "午餐", "餐饮", "", -1, TIME).toJson());
        assertInvalidMutation(record, "status", "支付成功");
        assertInvalidMutation(record, "reviewRequired", true);
        assertInvalidMutation(record, "reviewReason", "退款");
        assertInvalidMutation(record, "provider", "alipay");
        assertInvalidMutation(record, "provider", "wechat");
        assertInvalidMutation(record, "id", "manual:forged");
        assertInvalidMutation(record, "amountMinor", 1.5);
    }

    private static void assertInvalidMutation(JSONObject source, String key, Object value) throws Exception {
        JSONObject modified = new JSONObject(source.toString());
        modified.put(key, value);
        assertThrows(IllegalArgumentException.class, () -> Transaction.fromJson(modified.toString()));
    }
}
