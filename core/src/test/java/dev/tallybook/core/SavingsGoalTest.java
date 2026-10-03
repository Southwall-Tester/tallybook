package dev.tallybook.core;

import org.junit.Test;

import java.time.LocalDate;

import static org.junit.Assert.*;

public class SavingsGoalTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 3);

    @Test public void ProgressFloorsPercentAndDailyNeedRoundsUpInCents() {
        SavingsGoal goal = new SavingsGoal("新电脑", 10000, 3333, TODAY.plusDays(2));
        assertEquals(6667, goal.remainingMinor());
        assertEquals(33, goal.progressPercent());
        assertEquals(2223, goal.dailyNeedMinor(TODAY));
    }

    @Test public void TodayDeadlineAndOverdueBothRequireRemainingAmount() {
        SavingsGoal due = new SavingsGoal("旅行", 10000, 2000, TODAY);
        assertEquals(8000, due.dailyNeedMinor(TODAY));
        assertEquals(8000, due.dailyNeedMinor(TODAY.plusDays(1)));
    }

    @Test public void CompletedAndOverfundedGoalsClampProgressAndRemaining() {
        for (long saved : new long[]{10000, 12000}) {
            SavingsGoal goal = new SavingsGoal("旅行", 10000, saved, TODAY);
            assertEquals(0, goal.remainingMinor());
            assertEquals(100, goal.progressPercent());
            assertEquals(0, goal.dailyNeedMinor(TODAY));
            assertEquals(0, goal.dailyNeedMinor(TODAY.plusDays(1)));
        }
    }

    @Test public void ZeroSavedAndOneCentAcrossManyDaysAreHandledExactly() {
        SavingsGoal goal = new SavingsGoal("目标", 1, 0, TODAY.plusYears(10));
        assertEquals(0, goal.progressPercent());
        assertEquals(1, goal.dailyNeedMinor(TODAY));
        assertEquals(1, new SavingsGoal("目标", 1, 0, LocalDate.MAX).dailyNeedMinor(LocalDate.MIN));
    }

    @Test public void LeapDayCountsAsADayAndExactDivisionNeedsNoExtraCent() {
        SavingsGoal goal = new SavingsGoal("春季旅行", 300, 0, LocalDate.of(2024, 3, 1));
        assertEquals(100, goal.dailyNeedMinor(LocalDate.of(2024, 2, 28)));
        assertEquals(150, goal.dailyNeedMinor(LocalDate.of(2024, 2, 29)));
    }

    @Test public void GoalValidatesNamesMoneyAndRequiredDates() {
        for (String name : new String[]{"", " ", "目标 ", "目\n标", "x".repeat(129)}) {
            assertThrows(IllegalArgumentException.class, () -> new SavingsGoal(name, 100, 0, TODAY));
        }
        assertThrows(IllegalArgumentException.class, () -> new SavingsGoal("目标", 0, 0, TODAY));
        assertThrows(IllegalArgumentException.class, () -> new SavingsGoal("目标", -1, 0, TODAY));
        assertThrows(IllegalArgumentException.class, () -> new SavingsGoal("目标", 100, -1, TODAY));
        assertThrows(IllegalArgumentException.class, () -> new SavingsGoal("目标", Transaction.MAX_ABS_AMOUNT_MINOR + 1, 0, TODAY));
        assertThrows(IllegalArgumentException.class, () -> new SavingsGoal("目标", 100, Transaction.MAX_ABS_AMOUNT_MINOR + 1, TODAY));
        assertThrows(NullPointerException.class, () -> new SavingsGoal("目标", 100, 0, null));
    }

    @Test public void LargestPermittedValuesDoNotOverflowPercentage() {
        SavingsGoal goal = new SavingsGoal("目标", Transaction.MAX_ABS_AMOUNT_MINOR,
                Transaction.MAX_ABS_AMOUNT_MINOR - 1, TODAY);
        assertEquals(99, goal.progressPercent());
        assertEquals(1, goal.remainingMinor());
        assertEquals(1, goal.dailyNeedMinor(TODAY));
    }
}
