package dev.tallybook.core;

import org.json.JSONObject;
import org.junit.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collections;

import static org.junit.Assert.*;

/** Entirely fictional screen candidates; no account identifiers or real bills. */
public class ScreenTransactionTest {
    private static final long TIME = 1_791_000_000_000L;

    @Test public void screenStartsPendingAndRoundTripsAtMinutePrecision() {
        Transaction original = Transaction.screen("虚构食堂", -1280, TIME + 59_999);
        Transaction restored = Transaction.fromJson(original.toJson());
        assertEquals(Transaction.SCREEN_PROVIDER, restored.provider);
        assertEquals(Transaction.SCREEN_STATUS, restored.status);
        assertEquals(Transaction.SCREEN_DESCRIPTION, restored.description);
        assertEquals(Transaction.SCREEN_REVIEW_REASON, restored.reviewReason);
        assertTrue(restored.reviewRequired);
        assertEquals("", restored.tradeId);
        assertEquals("", restored.paymentMethod);
        assertEquals(-1280, restored.amountMinor);
        assertEquals(TIME, restored.occurredAt);
        assertEquals(original.id, restored.id);
        assertTrue(restored.id.matches("wechat_screen:[0-9a-f]{64}"));
    }

    @Test public void explicitConfirmationPreservesIdentityAndOrigin() {
        Transaction pending = Transaction.screen("虚构食堂", -1280, TIME);
        Transaction confirmed = Transaction.fromJson(pending.confirmScreen().toJson());
        assertEquals(pending.id, confirmed.id);
        assertEquals(pending.provider, confirmed.provider);
        assertEquals(pending.counterparty, confirmed.counterparty);
        assertEquals(pending.description, confirmed.description);
        assertEquals(pending.occurredAt, confirmed.occurredAt);
        assertEquals(pending.amountMinor, confirmed.amountMinor);
        assertEquals(Transaction.SCREEN_CONFIRMED_STATUS, confirmed.status);
        assertEquals("", confirmed.reviewReason);
        assertFalse(confirmed.reviewRequired);
        assertTrue(pending.reviewRequired);
        assertEquals(confirmed.toJson(), confirmed.confirmScreen().toJson());
    }

    @Test public void onlyScreenProviderCanUseConfirmation() {
        Transaction manual = Transaction.manual("fictional-id", "虚构收入", "其他", "", 100, TIME);
        Transaction wechat = new Transaction("fictional-id", "虚构商户", "支付成功", "零钱", "",
                -100, TIME, false, "");
        assertThrows(IllegalArgumentException.class, manual::confirmScreen);
        assertThrows(IllegalArgumentException.class, wechat::confirmScreen);
    }

    @Test public void repeatedCaptureHasStableIdButMinuteCollisionIsExplicit() {
        Transaction first = Transaction.screen("虚构食堂", -1280, TIME);
        assertEquals(first.id, Transaction.screen("虚构食堂", -1280, TIME + 59_999).id);
        assertNotEquals(first.id, Transaction.screen("虚构食堂", -1280, TIME + 60_000).id);
        assertNotEquals(first.id, Transaction.screen("虚构书店", -1280, TIME).id);
        assertNotEquals(first.id, Transaction.screen("虚构食堂", -1281, TIME).id);
        assertNotEquals(first.id, Transaction.screen("虚构食堂", 1280, TIME).id);
        assertTrue(first.reviewReason.contains("同一分钟同标题同金额可能合并"));
    }

    @Test public void screenFingerprintDoesNotCollideWithWechatOrManualNamespaces() {
        Transaction screen = Transaction.screen("虚构食堂", -1280, TIME);
        Transaction wechat = new Transaction("", screen.counterparty, screen.status, screen.paymentMethod,
                screen.description, screen.amountMinor, screen.occurredAt, true, screen.reviewReason);
        Transaction manual = Transaction.manual("fictional-id", "虚构食堂", "其他", "", -1280, TIME);
        assertNotEquals(screen.id, wechat.id);
        assertNotEquals(screen.id, manual.id);
    }

    @Test public void unknownOrContradictoryScreenStatesAreRejected() throws Exception {
        JSONObject pending = new JSONObject(Transaction.screen("虚构食堂", -1280, TIME).toJson());
        invalidMutation(pending, "status", "支付成功");
        invalidMutation(pending, "status", Transaction.SCREEN_CONFIRMED_STATUS);
        invalidMutation(pending, "status", "");
        invalidMutation(pending, "reviewRequired", false);
        invalidMutation(pending, "reviewReason", "");
        invalidMutation(pending, "reviewReason", "任意说明");
        invalidMutation(pending, "tradeId", "fake-order");
        invalidMutation(pending, "paymentMethod", "零钱");
        invalidMutation(pending, "description", "其他来源");
        invalidMutation(pending, "occurredAt", TIME + 1);
        invalidMutation(pending, "provider", "unknown");
        invalidMutation(pending, "id", "wechat_screen:forged");
        JSONObject confirmed = new JSONObject(Transaction.screen("虚构食堂", -1280, TIME).confirmScreen().toJson());
        invalidMutation(confirmed, "status", Transaction.SCREEN_STATUS);
        invalidMutation(confirmed, "status", "支付成功");
        invalidMutation(confirmed, "reviewRequired", true);
        invalidMutation(confirmed, "reviewReason", Transaction.SCREEN_REVIEW_REASON);
        assertThrows(IllegalArgumentException.class, () -> Transaction.fromJson("{}"));
    }

    @Test public void amountAndTimeBoundsAreCheckedBeforeMinuteTruncation() {
        for (long amount : new long[]{0, Long.MIN_VALUE, Long.MAX_VALUE,
                Transaction.MAX_ABS_AMOUNT_MINOR + 1, -Transaction.MAX_ABS_AMOUNT_MINOR - 1}) {
            assertThrows(IllegalArgumentException.class, () -> Transaction.screen("虚构记录", amount, TIME));
        }
        for (long time : new long[]{Long.MIN_VALUE, Long.MAX_VALUE, Transaction.MIN_OCCURRED_AT - 1,
                Transaction.MAX_OCCURRED_AT, Transaction.MAX_OCCURRED_AT + 1}) {
            assertThrows(IllegalArgumentException.class, () -> Transaction.screen("虚构记录", 1, time));
        }
        assertEquals(Transaction.MIN_OCCURRED_AT,
                Transaction.screen("虚构收入", Transaction.MAX_ABS_AMOUNT_MINOR, Transaction.MIN_OCCURRED_AT).occurredAt);
        assertEquals(Transaction.MAX_OCCURRED_AT - 60_000,
                Transaction.screen("虚构支出", -Transaction.MAX_ABS_AMOUNT_MINOR, Transaction.MAX_OCCURRED_AT - 1).occurredAt);
    }

    @Test public void screenTitleValidationDoesNotSilentlyRewriteOcrText() {
        for (String title : new String[]{null, "", " ", " 前后空格 ", "虚构\n换行", "虚构\t制表", "x".repeat(1025)}) {
            assertThrows(IllegalArgumentException.class, () -> Transaction.screen(title, -1, TIME));
        }
    }

    @Test public void newScreenTitlesIgnoreOnlyHorizontalSpacesBetweenHanCharacters() {
        Transaction expected = Transaction.screen("虚构早餐店", -1280, TIME);
        for (String title : new String[]{"虚构 早餐店", "虚 构  早 餐 店", "虚构\u00a0早餐店",
                "虚构\u3000早餐店", "虚构 \u00a0\u3000早餐店"}) {
            Transaction normalized = Transaction.screen(title, -1280, TIME);
            assertEquals(expected.counterparty, normalized.counterparty);
            assertEquals(expected.id, normalized.id);
            assertEquals(expected.amountMinor, normalized.amountMinor);
            assertEquals(expected.occurredAt, normalized.occurredAt);
            assertTrue(normalized.reviewRequired);
        }
        String supplementaryHan = new String(Character.toChars(0x20000));
        assertEquals("虚构" + supplementaryHan + "店",
                Transaction.normalizeScreenTitle("虚构 " + supplementaryHan + " 店"));
    }

    @Test public void newScreenTitlesPreserveEnglishDigitPunctuationAndMixedScriptSpaces() {
        for (String title : new String[]{"Fictional Coffee Shop", "虚构 A 店", "7 11 便利店", "虚构 · 早餐店",
                "虚构 … 早餐店", "虚构 18.50 店"}) {
            Transaction original = Transaction.screen(title, -1280, TIME);
            assertEquals(title, original.counterparty);
            assertNotEquals(original.id, Transaction.screen(title.replace(" ", ""), -1280, TIME).id);
        }
        assertEquals("虚构 A 早餐店", Transaction.normalizeScreenTitle("虚 构 A 早 餐 店"));
    }

    @Test public void oldSerializedScreenTitleAndIdentityStillReadAndConfirmUnchanged() throws Exception {
        // Frozen pre-normalization identity for an entirely fictional historical record.
        String oldId = "wechat_screen:8f0d75e960c0ccbcf0b7f48c2b8fd7d6c5a94b34cba0f74e81f2be633e658682";
        JSONObject legacy = new JSONObject(Transaction.screen("虚构早餐店", -1280, TIME).toJson())
                .put("counterparty", "虚构 早餐店").put("id", oldId);
        Transaction restored = Transaction.fromJson(legacy.toString());
        assertEquals("虚构 早餐店", restored.counterparty);
        assertEquals(oldId, restored.id);
        assertEquals(oldId, restored.confirmScreen().id);
        assertEquals("虚构 早餐店", Transaction.fromJson(restored.confirmScreen().toJson()).counterparty);
        assertNotEquals(oldId, Transaction.screen(restored.counterparty, -1280, TIME).id);
    }

    @Test public void confirmedScreenEntersTotalsAndPendingScreenDoesNot() {
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        LocalDate date = Instant.ofEpochMilli(TIME).atZone(zone).toLocalDate();
        BudgetPlan plan = new BudgetPlan(date, date, 10000, 0, 0);
        Transaction screen = Transaction.screen("虚构食堂", -1280, TIME);
        BudgetEngine.Snapshot pending = BudgetEngine.summarize(plan, Collections.singletonList(screen), date, zone);
        assertEquals(1, pending.pendingCount);
        assertEquals(0, pending.expenseMinor);
        BudgetEngine.Snapshot confirmed = BudgetEngine.summarize(plan,
                Collections.singletonList(screen.confirmScreen()), date, zone);
        assertEquals(0, confirmed.pendingCount);
        assertEquals(1280, confirmed.expenseMinor);
        assertEquals(8720, confirmed.remainingMinor);
    }

    @Test public void explicitScreenReviewDoesNotRelaxWechatTransferRestrictions() {
        Transaction screen = Transaction.screen("虚构家人转账", 10000, TIME);
        assertFalse(screen.confirmScreen().reviewRequired);
        assertThrows(IllegalArgumentException.class, () -> new Transaction(
                "fictional-transfer", "虚构家人转账", "支付成功", "零钱", "", 10000, TIME, false, ""));
    }

    private static void invalidMutation(JSONObject source, String field, Object value) throws Exception {
        JSONObject modified = new JSONObject(source.toString()).put(field, value);
        assertThrows(IllegalArgumentException.class, () -> Transaction.fromJson(modified.toString()));
    }
}
