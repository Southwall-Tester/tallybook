package dev.tallybook.core;

import org.junit.Test;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;

public class MoneyCoachTest {
    private static final LocalDate MONDAY = LocalDate.of(2026, 9, 28);
    private static final long NOW = Instant.parse("2026-10-03T10:00:00Z").toEpochMilli();

    @Test public void bookExampleAllocatesExactlyAndExecutionDoesNotChangeAmounts() {
        MoneyCoach.Allocation allocation = MoneyCoach.allocate(7400, 50, 40, 10);
        assertEquals(3700, allocation.gooseMinor);
        assertEquals(2960, allocation.dreamMinor);
        assertEquals(740, allocation.dailyMinor);
        MoneyCoach.AllocationRecord planned = new MoneyCoach.AllocationRecord("plan", 7400, 50, 40, 10, NOW, 0, "虚构计划");
        MoneyCoach.AllocationRecord done = new MoneyCoach.AllocationRecord("plan", 7400, 50, 40, 10, NOW, NOW + 1, "自行记录");
        assertFalse(planned.isExecuted());
        assertTrue(done.isExecuted());
        assertEquals(planned.allocation.totalMinor(), done.allocation.totalMinor());
    }

    @Test public void everyWholePercentSplitConservesSmallAmountsAndMaximum() {
        long[] amounts = {0, 1, 2, 3, 7, 99, 101, 999, MoneyCoach.MAX_AMOUNT_MINOR};
        for (int goose = 0; goose <= 100; goose++) {
            for (int dream = 0; dream <= 100 - goose; dream++) {
                int daily = 100 - goose - dream;
                for (long amount : amounts) {
                    MoneyCoach.Allocation a = MoneyCoach.allocate(amount, goose, dream, daily);
                    assertEquals(amount, a.totalMinor());
                    assertTrue(a.gooseMinor >= 0 && a.dreamMinor >= 0 && a.dailyMinor >= 0);
                    if (goose == 0) assertEquals(0, a.gooseMinor);
                    if (dream == 0) assertEquals(0, a.dreamMinor);
                    if (daily == 0) assertEquals(0, a.dailyMinor);
                    assertTrue(Math.abs(a.gooseMinor * 100 - amount * goose) < 100);
                    assertTrue(Math.abs(a.dreamMinor * 100 - amount * dream) < 100);
                    assertTrue(Math.abs(a.dailyMinor * 100 - amount * daily) < 100);
                }
            }
        }
    }

    @Test public void oneCentAndTiesHaveDeterministicRounding() {
        assertEquals(1, MoneyCoach.allocate(1, 50, 40, 10).gooseMinor);
        MoneyCoach.Allocation tie = MoneyCoach.allocate(1, 50, 50, 0);
        assertEquals(1, tie.gooseMinor);
        assertEquals(0, tie.dreamMinor);
        MoneyCoach.Allocation split = MoneyCoach.allocate(2, 34, 33, 33);
        assertEquals(1, split.gooseMinor);
        assertEquals(1, split.dreamMinor);
        assertEquals(0, split.dailyMinor);
    }

    @Test public void invalidAmountsAndRatiosCannotCreateAnAllocation() {
        for (long amount : new long[]{-1, MoneyCoach.MAX_AMOUNT_MINOR + 1, Long.MAX_VALUE, Long.MIN_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> MoneyCoach.allocate(amount, 50, 40, 10));
        }
        for (int[] ratios : new int[][]{{50, 40, 9}, {50, 40, 11}, {-1, 50, 51}, {101, 0, -1}, {Integer.MAX_VALUE, 1, 1}}) {
            assertThrows(IllegalArgumentException.class, () -> MoneyCoach.allocate(1, ratios[0], ratios[1], ratios[2]));
        }
        assertThrows(IllegalArgumentException.class, () -> new MoneyCoach.AllocationRecord("a", 0, 50, 40, 10, NOW, 0, ""));
        assertThrows(IllegalArgumentException.class, () -> new MoneyCoach.AllocationRecord("a", 1, 50, 40, 10, NOW, NOW - 1, ""));
    }

    @Test public void actionsExpireAtThePreciseDeadlineAndCanCompleteAfterIt() {
        MoneyCoach.Action action = new MoneyCoach.Action("action-1", "拍一张目标照片", NOW, 0, "还未找到合适时间");
        assertEquals(NOW + 259200000L, action.deadlineAt);
        assertEquals("OPEN", action.status(action.deadlineAt - 1));
        assertEquals("OVERDUE", action.status(action.deadlineAt));
        MoneyCoach.Action done = action.complete(action.deadlineAt + 1);
        assertEquals("DONE", done.status(action.deadlineAt + 2));
        assertEquals(action.id, done.id);
        assertEquals(action.deadlineAt, done.deadlineAt);
        assertEquals(action.obstacle, done.obstacle);
    }

    @Test public void actionWindowUsesElapsedHoursAcrossDstAndLeapDay() {
        ZonedDateTime start = ZonedDateTime.of(2026, 3, 7, 12, 0, 0, 0, ZoneId.of("America/New_York"));
        long epoch = start.toInstant().toEpochMilli();
        long deadline = MoneyCoach.deadlineAfter72Hours(epoch);
        assertEquals(72 * 3600_000L, deadline - epoch);
        assertNotEquals(start.plusDays(3).toInstant().toEpochMilli(), deadline);
        long leap = Instant.parse("2024-02-28T10:00:00Z").toEpochMilli();
        assertEquals(Instant.parse("2024-03-02T10:00:00Z").toEpochMilli(), MoneyCoach.deadlineAfter72Hours(leap));
    }

    @Test public void actionTimesAndEmptyTasksAreRejectedWithoutOverflow() {
        assertThrows(IllegalArgumentException.class, () -> MoneyCoach.deadlineAfter72Hours(Long.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> MoneyCoach.deadlineAfter72Hours(Transaction.MAX_OCCURRED_AT - 1));
        assertThrows(IllegalArgumentException.class, () -> new MoneyCoach.Action("a", " ", NOW, 0, ""));
        assertThrows(IllegalArgumentException.class, () -> new MoneyCoach.Action("a", "做一件小事", NOW, NOW - 1, ""));
        assertThrows(IllegalArgumentException.class, () -> new MoneyCoach.Action("a", "做一件小事", NOW, 0, "").complete(0));
        long latest = Transaction.MAX_OCCURRED_AT - MoneyCoach.ACTION_WINDOW_MILLIS - 1;
        assertEquals(Transaction.MAX_OCCURRED_AT - 1, MoneyCoach.deadlineAfter72Hours(latest));
    }

    @Test public void sevenThemesKeepBookOrderAndRepeatAcrossYears() {
        List<String> expected = Arrays.asList("友好亲和", "勇于承担", "善待他人", "帮助给予", "感恩之心", "勤学不辍", "值得信赖");
        for (int day = 0; day < 800; day++) {
            MoneyCoach.Theme theme = MoneyCoach.themeFor(MONDAY, MONDAY.plusDays(day));
            assertEquals(day % 7, theme.index);
            assertEquals(expected.get(day % 7), theme.title);
            assertFalse(theme.explanation.isEmpty());
            assertFalse(theme.prompt.isEmpty());
        }
        assertThrows(IllegalArgumentException.class, () -> MoneyCoach.themeFor(MONDAY, MONDAY.minusDays(1)));
        assertThrows(UnsupportedOperationException.class, () -> MoneyCoach.THEMES.clear());
    }

    @Test public void weekStartHandlesSundayLeapDateAndYearBoundary() {
        assertEquals(LocalDate.of(2023, 12, 25), MoneyCoach.weekStart(LocalDate.of(2023, 12, 31)));
        assertEquals(LocalDate.of(2024, 1, 1), MoneyCoach.weekStart(LocalDate.of(2024, 1, 1)));
        assertEquals(LocalDate.of(2024, 2, 26), MoneyCoach.weekStart(LocalDate.of(2024, 2, 29)));
        assertThrows(IllegalArgumentException.class, () -> new MoneyCoach.WeeklyReview(MONDAY.plusDays(1), "", "", ""));
    }

    @Test public void wishDraftsAndOverfundedGoalsAreValidWithoutInventingAMoneyTransfer() {
        MoneyCoach.Wish draft = MoneyCoach.Wish.empty(0);
        assertNull(draft.targetDate);
        assertEquals(0, draft.targetMinor);
        MoneyCoach.Wish over = new MoneyCoach.Wish(1, "旅行", "与朋友相处", true, 100, 200, MONDAY, "content://local/picture/1");
        assertEquals(0, over.remainingMinor());
        assertEquals(200, over.savedMinor);
        assertThrows(IllegalArgumentException.class, () -> new MoneyCoach.Wish(1, "", "", true, 0, 0, null, ""));
        assertThrows(IllegalArgumentException.class, () -> new MoneyCoach.Wish(10, "", "", false, 0, 0, null, ""));
        assertThrows(IllegalArgumentException.class, () -> new MoneyCoach.Wish(1, "旅行", "", false, 0, -1, null, ""));
        assertThrows(IllegalArgumentException.class, () -> new MoneyCoach.Wish(1, "旅行", "", false, 0, 0, null, "https://example.test/private.jpg"));
    }

    @Test public void stateHasTenStableSlotsAndAtMostThreePriorities() {
        List<MoneyCoach.Wish> wishes = new ArrayList<>();
        for (int i = 0; i < 3; i++) wishes.add(new MoneyCoach.Wish(i, "梦想" + i, "", true, 0, 0, null, ""));
        MoneyCoach.State state = withWishes(wishes);
        assertEquals(10, state.wishes.size());
        assertEquals(9, state.wishes.get(9).slot);
        wishes.add(new MoneyCoach.Wish(3, "梦想3", "", true, 0, 0, null, ""));
        assertThrows(IllegalArgumentException.class, () -> withWishes(wishes));
        assertEquals(3, state.wishes.stream().filter(w -> w.priority).count());
        assertThrows(IllegalArgumentException.class, () -> withWishes(Arrays.asList(MoneyCoach.Wish.empty(0), MoneyCoach.Wish.empty(0))));
        assertThrows(UnsupportedOperationException.class, () -> state.wishes.clear());
    }

    @Test public void journalAllowsIncompleteDraftAndMoreThanFiveWithoutSharingMutableLists() {
        List<String> items = new ArrayList<>(Arrays.asList("完成了一步", ""));
        MoneyCoach.SuccessEntry entry = new MoneyCoach.SuccessEntry(MONDAY, items);
        items.add("后加的不应污染已保存快照");
        assertEquals(2, entry.items.size());
        assertEquals(0, new MoneyCoach.SuccessEntry(MONDAY, Collections.emptyList()).items.size());
        assertEquals(6, new MoneyCoach.SuccessEntry(MONDAY, Collections.nCopies(6, "小事也算")).items.size());
        assertThrows(IllegalArgumentException.class, () -> new MoneyCoach.SuccessEntry(MONDAY, Collections.nCopies(101, "")));
        assertThrows(UnsupportedOperationException.class, () -> entry.items.clear());
    }

    @Test public void datesAndTextAreValidatedBeforeStorage() {
        assertThrows(IllegalArgumentException.class, () -> new MoneyCoach.SuccessEntry(LocalDate.of(2100, 1, 1), Collections.emptyList()));
        assertThrows(IllegalArgumentException.class, () -> new MoneyCoach.SuccessEntry(null, Collections.emptyList()));
        assertThrows(IllegalArgumentException.class, () -> new MoneyCoach.SuccessEntry(MONDAY, Arrays.asList((String) null)));
        assertThrows(IllegalArgumentException.class, () -> new MoneyCoach.SuccessEntry(MONDAY, Arrays.asList("bad\u0000value")));
        assertEquals("第一行\n第二行", new MoneyCoach.SuccessEntry(MONDAY, Arrays.asList("第一行\n第二行")).items.get(0));
        assertThrows(IllegalArgumentException.class, () -> new MoneyCoach.PracticeEntry(MONDAY, 7, "", ""));
    }

    @Test public void compoundExamplesUseCentsAndPermitLossScenarios() {
        assertEquals(309000, MoneyCoach.compoundMinor(300000, new BigDecimal("3"), 1));
        assertEquals(318270, MoneyCoach.compoundMinor(300000, new BigDecimal("3"), 2));
        assertEquals(8100, MoneyCoach.compoundMinor(10000, new BigDecimal("-10"), 2));
        assertEquals(0, MoneyCoach.compoundMinor(10000, new BigDecimal("-100"), 1));
        assertEquals(10000, MoneyCoach.compoundMinor(10000, BigDecimal.ZERO, 100));
        assertEquals(10000, MoneyCoach.compoundMinor(10000, new BigDecimal("99"), 0));
        assertThrows(IllegalArgumentException.class, () -> MoneyCoach.compoundMinor(MoneyCoach.MAX_AMOUNT_MINOR, BigDecimal.ONE, 1));
        assertThrows(IllegalArgumentException.class, () -> MoneyCoach.compoundMinor(1, new BigDecimal("-101"), 1));
        assertThrows(IllegalArgumentException.class, () -> MoneyCoach.compoundMinor(1, BigDecimal.ONE, -1));
    }

    @Test public void ruleOf72UsesPercentagePointsAndDoesNotInventAZeroRateResult() {
        assertEquals(6.0, MoneyCoach.ruleOf72Years(12), 0.000001);
        assertEquals(4.8, MoneyCoach.ruleOf72Years(15), 0.000001);
        assertEquals(24.0, MoneyCoach.ruleOf72Years(3), 0.000001);
        for (double rate : new double[]{0, -1, Double.NaN, Double.POSITIVE_INFINITY, 101}) {
            assertThrows(IllegalArgumentException.class, () -> MoneyCoach.ruleOf72Years(rate));
        }
    }

    private static MoneyCoach.State withWishes(List<MoneyCoach.Wish> wishes) {
        return new MoneyCoach.State(MONDAY, 50, 40, 10, wishes, Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
    }
}
