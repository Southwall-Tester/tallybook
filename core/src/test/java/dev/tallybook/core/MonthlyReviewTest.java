package dev.tallybook.core;

import org.junit.Test;
import java.time.YearMonth;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;

public final class MonthlyReviewTest {
    @Test public void roundTripPreservesUserTextAndSortsMonths() {
        MonthlyReview first = new MonthlyReview(YearMonth.of(2026, 1), "第一行\n第二行", "原因", "再试一次");
        MonthlyReview second = new MonthlyReview(YearMonth.of(2026, 2), "", "", "");
        java.util.List<MonthlyReview> copy = MonthlyReview.decode(MonthlyReview.encode(Arrays.asList(first, second)));
        assertEquals(second.month, copy.get(0).month); assertEquals(first.facts, copy.get(1).facts);
        assertThrows(UnsupportedOperationException.class, () -> copy.clear());
    }
    @Test public void duplicateMonthOrOutOfRangeRejected() {
        MonthlyReview r = new MonthlyReview(YearMonth.of(2026, 1), "", "", "");
        assertThrows(IllegalArgumentException.class, () -> MonthlyReview.encode(Arrays.asList(r, r)));
        assertThrows(IllegalArgumentException.class, () -> new MonthlyReview(YearMonth.of(2100, 1), "", "", ""));
        assertThrows(IllegalArgumentException.class, () -> new MonthlyReview(YearMonth.of(2026, 1), "x".repeat(8001), "", ""));
    }
    @Test public void corruptionAndUnknownSchemaNeverBecomeEmptyState() {
        for (String raw : Arrays.asList("{}", "{\"schemaVersion\":2,\"records\":[]}",
                "{\"schemaVersion\":1,\"schemaVersion\":1,\"records\":[]}", "{\"schemaVersion\":1.0,\"records\":[]}",
                "{\"schemaVersion\":1,\"records\":[],\"unknown\":true}", "{\"schemaVersion\":1,\"records\":[]}garbage"))
            assertThrows(IllegalStateException.class, () -> MonthlyReview.decode(raw));
        assertTrue(MonthlyReview.decode(MonthlyReview.encode(Collections.emptyList())).isEmpty());
    }
    @Test public void allowsMultilineAndEmojiButRejectsHiddenControls() {
        assertEquals("一\n二\t🙂", new MonthlyReview(YearMonth.of(2026, 1), "一\n二\t🙂", "", "").facts);
        assertThrows(IllegalArgumentException.class, () -> new MonthlyReview(YearMonth.of(2026, 1), "x\u0000y", "", ""));
        assertThrows(IllegalArgumentException.class, () -> new MonthlyReview(YearMonth.of(2026, 1), "\uD800", "", ""));
    }
}
