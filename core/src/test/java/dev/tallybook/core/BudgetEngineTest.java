package dev.tallybook.core;

import org.junit.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.*;

public class BudgetEngineTest {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final LocalDate START = LocalDate.of(2026, 10, 1);
    private static final LocalDate END = LocalDate.of(2026, 10, 31);

    @Test public void confirmedIncomeExpenseAndReservesProduceSpendableBalance() {
        BudgetPlan plan = new BudgetPlan(START, END, 200000, 50000, 30000);
        BudgetEngine.Snapshot summary = BudgetEngine.summarize(plan, Arrays.asList(
                entry("income", 12000, START), entry("expense", -1280, START.plusDays(1))),
                START.plusDays(2), SHANGHAI);
        assertEquals(12000, summary.incomeMinor);
        assertEquals(1280, summary.expenseMinor);
        assertEquals(130720, summary.remainingMinor);
        assertEquals(29, summary.daysLeft);
        assertEquals(4507, summary.dailyMinor);
        assertEquals(0, summary.pendingCount);
        assertTrue(summary.active);
    }

    @Test public void refundsPendingAndFutureOrOutsideRecordsDoNotEnterTotals() {
        long time = START.atStartOfDay(SHANGHAI).toInstant().toEpochMilli();
        Transaction refund = new Transaction("refund", "书店", "已全额退款", "零钱", "", -4800, time, true, "退款待核对");
        Transaction pending = new Transaction("pending", "书店", "等待付款", "零钱", "", -2000, time, true, "等待付款");
        BudgetEngine.Snapshot summary = BudgetEngine.summarize(new BudgetPlan(START, END, 10000, 0, 0),
                Arrays.asList(refund, pending, entry("before", -100, START.minusDays(1)),
                        entry("future", -100, START.plusDays(1)), entry("after", -100, END.plusDays(1))), START, SHANGHAI);
        assertEquals(2, summary.pendingCount);
        assertEquals(0, summary.incomeMinor);
        assertEquals(0, summary.expenseMinor);
        assertEquals(10000, summary.remainingMinor);
    }

    @Test public void negativeAndZeroBalancesHaveZeroDailyAllowance() {
        BudgetPlan plan = new BudgetPlan(START, END, 10000, 2000, 3000);
        BudgetEngine.Snapshot negative = BudgetEngine.summarize(plan, Collections.singletonList(entry("a", -6000, START)), START, SHANGHAI);
        assertEquals(-1000, negative.remainingMinor);
        assertEquals(0, negative.dailyMinor);
        BudgetEngine.Snapshot zero = BudgetEngine.summarize(plan, Collections.singletonList(entry("a", -5000, START)), START, SHANGHAI);
        assertEquals(0, zero.remainingMinor);
        assertEquals(0, zero.dailyMinor);
    }

    @Test public void inclusiveStartEndAndSingleDayPeriodAreActive() {
        BudgetPlan plan = new BudgetPlan(START, END, 101, 0, 0);
        assertEquals(31, BudgetEngine.summarize(plan, Collections.emptyList(), START, SHANGHAI).daysLeft);
        BudgetEngine.Snapshot last = BudgetEngine.summarize(plan, Collections.singletonList(entry("last", -1, END)), END, SHANGHAI);
        assertEquals(1, last.daysLeft);
        assertEquals(100, last.dailyMinor);
        assertTrue(last.active);
        BudgetEngine.Snapshot single = BudgetEngine.summarize(new BudgetPlan(START, START, 101, 0, 0), Collections.emptyList(), START, SHANGHAI);
        assertEquals(101, single.dailyMinor);
    }

    @Test public void OutsidePeriodDisablesAllowanceButPastPeriodKeepsActualTotals() {
        BudgetPlan plan = new BudgetPlan(START, END, 10000, 0, 0);
        Transaction expense = entry("a", -5000, START);
        BudgetEngine.Snapshot before = BudgetEngine.summarize(plan, Collections.singletonList(expense), START.minusDays(1), SHANGHAI);
        assertFalse(before.active);
        assertEquals(0, before.daysLeft);
        assertEquals(0, before.dailyMinor);
        assertEquals(0, before.expenseMinor);
        assertEquals(10000, before.remainingMinor);
        BudgetEngine.Snapshot after = BudgetEngine.summarize(plan, Collections.singletonList(expense), END.plusDays(1), SHANGHAI);
        assertFalse(after.active);
        assertEquals(0, after.daysLeft);
        assertEquals(0, after.dailyMinor);
        assertEquals(5000, after.expenseMinor);
        assertEquals(5000, after.remainingMinor);
    }

    @Test public void LedgerDatesUseSuppliedTimezoneAroundMidnight() {
        Transaction nearMidnight = Transaction.manual("a", "夜宵", "餐饮", "", -100,
                Instant.parse("2026-09-30T16:30:00Z").toEpochMilli());
        BudgetPlan plan = new BudgetPlan(START, END, 10000, 0, 0);
        assertEquals(100, BudgetEngine.summarize(plan, Collections.singletonList(nearMidnight), START, SHANGHAI).expenseMinor);
        assertEquals(0, BudgetEngine.summarize(plan, Collections.singletonList(nearMidnight), START, ZoneId.of("UTC")).expenseMinor);
    }

    @Test public void PlanValidatesInclusiveLengthMoneyAndLeapYear() {
        new BudgetPlan(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31), 0, 0, 0);
        assertThrows(IllegalArgumentException.class, () -> new BudgetPlan(START, START.minusDays(1), 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new BudgetPlan(START, START.plusDays(366), 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new BudgetPlan(START, END, -1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new BudgetPlan(START, END, 0, -1, 0));
        assertThrows(IllegalArgumentException.class, () -> new BudgetPlan(START, END, 0, 0, Transaction.MAX_ABS_AMOUNT_MINOR + 1));
        new BudgetPlan(START, END, Transaction.MAX_ABS_AMOUNT_MINOR, Transaction.MAX_ABS_AMOUNT_MINOR, Transaction.MAX_ABS_AMOUNT_MINOR);
    }

    @Test public void IncomeAndExpenseOverflowFailInsteadOfWrapping() {
        int count = (int) (Long.MAX_VALUE / Transaction.MAX_ABS_AMOUNT_MINOR) + 1;
        BudgetPlan plan = new BudgetPlan(START, END, 0, 0, 0);
        assertThrows(ArithmeticException.class, () -> BudgetEngine.summarize(plan,
                Collections.nCopies(count, entry("largeIncome", Transaction.MAX_ABS_AMOUNT_MINOR, START)), START, SHANGHAI));
        assertThrows(ArithmeticException.class, () -> BudgetEngine.summarize(plan,
                Collections.nCopies(count, entry("largeExpense", -Transaction.MAX_ABS_AMOUNT_MINOR, START)), START, SHANGHAI));
    }

    @Test public void OpeningBalanceAdditionAndReserveSubtractionAlsoCheckOverflow() {
        long cap = Transaction.MAX_ABS_AMOUNT_MINOR;
        int count = (int) (Long.MAX_VALUE / cap);
        assertThrows(ArithmeticException.class, () -> BudgetEngine.summarize(new BudgetPlan(START, END, cap, 0, 0),
                Collections.nCopies(count, entry("income", cap, START)), START, SHANGHAI));
        assertThrows(ArithmeticException.class, () -> BudgetEngine.summarize(new BudgetPlan(START, END, 0, cap, cap),
                Collections.nCopies(count, entry("expense", -cap, START)), START, SHANGHAI));
    }

    private static Transaction entry(String id, long amount, LocalDate date) {
        return Transaction.manual(id, "测试账目", "其他", "", amount, date.atStartOfDay(SHANGHAI).toInstant().toEpochMilli());
    }
}
