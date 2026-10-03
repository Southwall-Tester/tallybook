package dev.tallybook.core;

import org.junit.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.*;

/** Entirely fictional OCR lines. No account screenshots or real transaction data. */
public class WechatScreenParserTest {
    private static final int WIDTH = 1080;
    private static final int HEIGHT = 2400;

    @Test public void parsesSignedCentsAndMinuteTimeAsReviewedRecords() {
        List<WechatScreenParser.TextLine> lines = page("2026年10月");
        row(lines, "虚构·校园食堂", "-18.50", "10月2日 10:14", 650);
        row(lines, "虚构·稿费", "+120.01", "10月1日 09:08", 850);
        WechatScreenParser.Result result = parse(lines);
        assertEquals("OK", result.code);
        assertEquals(0, result.skippedRows);
        assertEquals(2, result.transactions.size());
        Transaction expense = result.transactions.get(0);
        assertEquals(-1850, expense.amountMinor);
        assertEquals(time(2026, 10, 2, 10, 14), expense.occurredAt);
        assertEquals("虚构·校园食堂", expense.counterparty);
        assertEquals("", expense.tradeId);
        assertTrue(expense.reviewRequired);
        assertFalse(expense.reviewReason.isEmpty());
        assertEquals(12001, result.transactions.get(1).amountMinor);
    }

    @Test public void boundingBoxesDetermineRowsRatherThanOcrIterationOrder() {
        List<WechatScreenParser.TextLine> lines = page("2026年10月");
        row(lines, "虚构·甲店", "-0.29", "10月2日 10:14", 650);
        row(lines, "虚构·乙店", "-2.80", "10月1日 09:08", 850);
        Collections.shuffle(lines, new Random(17));
        WechatScreenParser.Result result = parse(lines);
        assertEquals(2, result.transactions.size());
        assertEquals("虚构·甲店", result.transactions.get(0).counterparty);
        assertEquals(-29, result.transactions.get(0).amountMinor);
        assertEquals(-280, result.transactions.get(1).amountMinor);
    }

    @Test public void assignsSeparateMonthsAndYearsFromVisibleHeaders() {
        List<WechatScreenParser.TextLine> lines = page("2026年1月");
        row(lines, "虚构·新年书店", "-12.80", "1月2日 10:14", 650);
        month(lines, "2025年12月", 850);
        row(lines, "虚构·去年饭店", "-38.00", "12月31日 19:08", 1000);
        WechatScreenParser.Result result = parse(lines);
        assertEquals(2, result.transactions.size());
        assertEquals(time(2026, 1, 2, 10, 14), result.transactions.get(0).occurredAt);
        assertEquals(time(2025, 12, 31, 19, 8), result.transactions.get(1).occurredAt);
    }

    @Test public void skipsUnsignedInternalTransfersAndBottomPartialRow() {
        List<WechatScreenParser.TextLine> lines = page("2026年10月");
        row(lines, "转入零钱通-来自零钱", "74.88", "10月2日 10:14", 650);
        row(lines, "虚构·正常店", "-8.50", "10月1日 09:08", 850);
        lines.add(line("虚构·半行店", 210, 2320, 820, 2360));
        lines.add(line("-9.50", 900, 2320, 1040, 2360));
        WechatScreenParser.Result result = parse(lines);
        assertEquals(1, result.transactions.size());
        assertEquals(2, result.skippedRows);
    }

    @Test public void ignoresMonthlySummaryAndPreservesEllipsisAndNumericMerchantNames() {
        List<WechatScreenParser.TextLine> lines = page("2026 年 10 月");
        lines.add(line("支出¥ 1800.00 收入¥ 2000.00", 550, 480, 1040, 520));
        row(lines, "虚构·很长的商户显示名称…", "-18.50", "10 月 2 日 10:14", 650);
        row(lines, "7号虚构饭堂", "-8.20", "10月1日 09:08", 850);
        WechatScreenParser.Result result = parse(lines);
        assertEquals(2, result.transactions.size());
        assertEquals("虚构·很长的商户显示名称…", result.transactions.get(0).counterparty);
        assertEquals("7号虚构饭堂", result.transactions.get(1).counterparty);
    }

    @Test public void refusesDetailsChatAndListWithoutItsToolbar() {
        for (String title : new String[]{"账单详情", "微信支付", "虚构聊天"}) {
            List<WechatScreenParser.TextLine> lines = page("2026年10月");
            lines.set(0, line(title, 50, 150, 300, 190));
            row(lines, "虚构店", "-1.00", "10月2日 10:14", 650);
            assertEquals("NOT_BILL_LIST", parse(lines).code);
        }
        List<WechatScreenParser.TextLine> lines = page("2026年10月");
        lines.remove(1);
        assertEquals("NOT_BILL_LIST", parse(lines).code);
        lines = page("2026年10月");
        lines.add(line("发送", 900, 2280, 1050, 2330));
        assertEquals("NOT_BILL_LIST", parse(lines).code);
    }

    @Test public void neverGuessesTheYearFromTheDeviceClock() {
        List<WechatScreenParser.TextLine> lines = page(null);
        row(lines, "虚构店", "-1.00", "10月2日 10:14", 650);
        WechatScreenParser.Result result = parse(lines);
        assertEquals("NEEDS_MONTH", result.code);
        assertTrue(result.transactions.isEmpty());
    }

    @Test public void rejectsBadAmountsWithoutCorrectingOcrCharactersOrGuessingSigns() {
        for (String value : new String[]{"18.50", "¥18.50", "-18.501", "+1e2", "-O.50",
                "＋18.50", "--18.50", "-18 .50", "-01.50", "-0.00", "+0",
                "-1,000.00", "-9999999999999.00"}) {
            List<WechatScreenParser.TextLine> lines = page("2026年10月");
            row(lines, "虚构店", value, "10月2日 10:14", 650);
            assertTrue(value, parse(lines).transactions.isEmpty());
        }
    }

    @Test public void acceptsExactWholeAndOneDecimalAmounts() {
        for (String value : new String[]{"-1", "-1.1", "+0.01", "+999999999999.99"}) {
            List<WechatScreenParser.TextLine> lines = page("2026年10月");
            row(lines, "虚构店", value, "10月2日 10:14", 650);
            assertEquals(value, 1, parse(lines).transactions.size());
        }
    }

    @Test public void acceptsOnlyKnownSingleMonthDropdownGlyphs() {
        for (String arrow : new String[]{"v", "V", "▼", "∨"}) {
            List<WechatScreenParser.TextLine> lines = page("2026年10月 " + arrow);
            row(lines, "虚构·十月店", "-18.50", "10月2日 10:14", 650);
            month(lines, "2026年9月", 855);
            row(lines, "虚构·九月店", "+18.88", "9月30日 20:13", 1010);
            WechatScreenParser.Result result = parse(lines);
            assertEquals(arrow, "OK", result.code);
            assertEquals(2, result.transactions.size());
            assertEquals(time(2026, 10, 2, 10, 14), result.transactions.get(0).occurredAt);
            assertEquals(time(2026, 9, 30, 20, 13), result.transactions.get(1).occurredAt);
        }
        for (String suffix : new String[]{"x", "vv", "▼v", "1", "其他文本", "支出未知"}) {
            List<WechatScreenParser.TextLine> lines = page("2026年10月" + suffix);
            row(lines, "虚构店", "-1.00", "10月2日 10:14", 650);
            assertEquals(suffix, "NEEDS_MONTH", parse(lines).code);
        }
    }

    @Test public void mathematicalMinusAndFullwidthTimeColonPreserveExactValues() {
        List<WechatScreenParser.TextLine> lines = page("2026年10月v");
        row(lines, "虚构店", "−18.50", "10月2日 10：14", 650);
        WechatScreenParser.Result result = parse(lines);
        assertEquals(1, result.transactions.size());
        assertEquals(-1850, result.transactions.get(0).amountMinor);
        assertEquals(time(2026, 10, 2, 10, 14), result.transactions.get(0).occurredAt);
        assertTrue(result.transactions.get(0).reviewRequired);
        for (String amount : new String[]{"−O.50", "−18.501", "− 18.50", "−0.00", "−−18.50"}) {
            lines = page("2026年10月v");
            row(lines, "虚构店", amount, "10月2日 10：14", 650);
            assertTrue(amount, parse(lines).transactions.isEmpty());
        }
    }

    @Test public void dropdownMonthStillSeedsOnlyAdjacentExactScrollAnchors() {
        WechatScreenParser.Session session = new WechatScreenParser.Session();
        List<WechatScreenParser.TextLine> first = page("2026年10月v");
        row(first, "虚构·锚点", "−18.50", "10月2日 10：14", 650);
        assertEquals("OK", session.parse(first, WIDTH, HEIGHT).code);
        List<WechatScreenParser.TextLine> next = page(null);
        row(next, "虚构·锚点", "-18.50", "10月2日 10:14", 450);
        row(next, "虚构·新行", "-2.00", "10月1日 09:08", 650);
        assertEquals(2, session.parse(next, WIDTH, HEIGHT).transactions.size());
    }

    @Test public void rejectsInvalidDatesAndHeaderMonthMismatch() {
        for (String date : new String[]{"10月32日 10:14", "10月2日 24:14", "10月2日 10:60",
                "9月2日 10:14", "10月2日", "昨天 10:14", "10月2日 1O:14", "10月2日 10:14:22"}) {
            List<WechatScreenParser.TextLine> lines = page("2026年10月");
            row(lines, "虚构店", "-1.00", date, 650);
            assertTrue(date, parse(lines).transactions.isEmpty());
        }
        List<WechatScreenParser.TextLine> leap = page("2025年2月");
        row(leap, "虚构店", "-1.00", "2月29日 10:14", 650);
        assertTrue(parse(leap).transactions.isEmpty());
    }

    @Test public void rejectsInvalidAndConflictingMonthHeaders() {
        for (String header : new String[]{"1999年10月", "2100年10月", "2026年13月"}) {
            List<WechatScreenParser.TextLine> lines = page(header);
            row(lines, "虚构店", "-1.00", "10月2日 10:14", 650);
            assertTrue(parse(lines).transactions.isEmpty());
        }
        List<WechatScreenParser.TextLine> lines = page("2026年10月");
        lines.add(line("2025年10月", 60, 485, 480, 525));
        row(lines, "虚构店", "-1.00", "10月2日 10:14", 650);
        assertEquals("NO_READABLE_ROWS", parse(lines).code);
    }

    @Test public void neverAttachesNextRowsDateOrPreviousMonthsYearToPartialRow() {
        List<WechatScreenParser.TextLine> lines = page("2026年10月");
        lines.add(line("虚构·缺日期", 210, 650, 820, 690));
        lines.add(line("-1.00", 900, 650, 1040, 690));
        row(lines, "虚构·完整", "-2.00", "10月1日 09:08", 850);
        WechatScreenParser.Result result = parse(lines);
        assertEquals(1, result.transactions.size());
        assertEquals("虚构·完整", result.transactions.get(0).counterparty);

        lines = page(null);
        row(lines, "虚构·上月尾行", "-1.00", "10月2日 10:14", 400);
        month(lines, "2026年9月", 620);
        row(lines, "虚构·完整", "-2.00", "9月30日 09:08", 850);
        result = parse(lines);
        assertEquals(1, result.transactions.size());
        assertEquals(time(2026, 9, 30, 9, 8), result.transactions.get(0).occurredAt);
    }

    @Test public void refusesAmbiguousNamesAmountsOrDatesWithinOneRow() {
        for (WechatScreenParser.TextLine extra : Arrays.asList(
                line("虚构·第二个名称", 220, 654, 700, 690),
                line("-2.00", 910, 654, 1040, 694),
                line("10月2日 10:15", 215, 780, 520, 810))) {
            List<WechatScreenParser.TextLine> lines = page("2026年10月");
            row(lines, "虚构店", "-1.00", "10月2日 10:14", 650);
            lines.add(extra);
            assertTrue(parse(lines).transactions.isEmpty());
        }
    }

    @Test public void validatesBoundsAndDoesNotAssociateDistantText() {
        List<WechatScreenParser.TextLine> lines = page("2026年10月");
        row(lines, "虚构店", "-1.00", "10月2日 10:14", 650);
        lines.set(lines.size() - 1, line("10月2日 10:14", -1, 730, 520, 765));
        assertTrue(parse(lines).transactions.isEmpty());
        lines.set(lines.size() - 1, line("10月2日 10:14", 210, 1500, 520, 1535));
        assertTrue(parse(lines).transactions.isEmpty());
        lines.add(null);
        lines.add(line(null, 0, 0, 5, 5));
        lines.add(line("invalid", 0, 0, WIDTH + 1, HEIGHT));
        assertEquals("NO_READABLE_ROWS", parse(lines).code);
        assertEquals("NOT_BILL_LIST", WechatScreenParser.parse(lines, 0, HEIGHT).code);
        assertEquals("NOT_BILL_LIST", WechatScreenParser.parse(null, WIDTH, HEIGHT).code);
    }

    @Test public void relativeGeometryAlsoWorksAtAnotherScreenshotResolution() {
        List<WechatScreenParser.TextLine> original = page("2026年10月");
        row(original, "虚构店", "-1.00", "10月2日 10:14", 650);
        List<WechatScreenParser.TextLine> scaled = new ArrayList<>();
        for (WechatScreenParser.TextLine line : original) scaled.add(line(line.text,
                line.left / 2, line.top / 2, line.right / 2, line.bottom / 2));
        assertEquals(1, WechatScreenParser.parse(scaled, WIDTH / 2, HEIGHT / 2).transactions.size());
    }

    @Test public void stableMinuteNameAmountIdentityCanMergeTwoIndistinguishablePayments() {
        List<WechatScreenParser.TextLine> first = page("2026年10月");
        row(first, "虚构店", "-1.00", "10月2日 10:14", 650);
        String id = parse(first).transactions.get(0).id;
        row(first, "虚构店", "-1.00", "10月2日 10:14", 850);
        WechatScreenParser.Result result = parse(first);
        assertEquals(1, result.transactions.size());
        assertEquals(id, result.transactions.get(0).id);
        first.add(first.get(first.size() - 1)); // Identical OCR box duplicated by caller.
        assertEquals(1, parse(first).transactions.size());
    }

    @Test public void sessionInheritsYearThroughAnExactVisibleOverlap() {
        WechatScreenParser.Session session = new WechatScreenParser.Session();
        assertEquals("OK", session.parse(anchorPage(), WIDTH, HEIGHT).code);
        List<WechatScreenParser.TextLine> scrolled = page(null);
        row(scrolled, "虚构·锚点", "-1.00", "10月2日 10:14", 450);
        row(scrolled, "虚构·新行", "-2.00", "10月1日 09:08", 650);
        WechatScreenParser.Result result = session.parse(scrolled, WIDTH, HEIGHT);
        assertEquals("OK", result.code);
        assertEquals(2, result.transactions.size());
        assertEquals(time(2026, 10, 1, 9, 8), result.transactions.get(1).occurredAt);
        List<WechatScreenParser.TextLine> further = page(null);
        row(further, "虚构·新行", "-2.00", "10月1日 09:08", 450);
        row(further, "虚构·更早", "-3.00", "10月1日 08:00", 650);
        assertEquals(2, session.parse(further, WIDTH, HEIGHT).transactions.size());
    }

    @Test public void sessionRequiresEveryAnchorFieldAndDoesNotUseAnOldNonadjacentFrame() {
        for (String[] changed : new String[][]{
                {"虚构·别的店", "-1.00", "10月2日 10:14"},
                {"虚构·锚点", "-1.01", "10月2日 10:14"},
                {"虚构·锚点", "-1.00", "10月2日 10:15"}}) {
            WechatScreenParser.Session session = new WechatScreenParser.Session();
            session.parse(anchorPage(), WIDTH, HEIGHT);
            List<WechatScreenParser.TextLine> next = page(null);
            row(next, changed[0], changed[1], changed[2], 450);
            assertEquals("NEEDS_MONTH", session.parse(next, WIDTH, HEIGHT).code);
            next = page(null);
            row(next, "虚构·锚点", "-1.00", "10月2日 10:14", 450);
            assertEquals("NEEDS_MONTH", session.parse(next, WIDTH, HEIGHT).code);
        }
    }

    @Test public void sessionNeverAssignsAnUnseenMonthsYearAcrossDecemberJanuary() {
        WechatScreenParser.Session session = new WechatScreenParser.Session();
        List<WechatScreenParser.TextLine> first = page("2026年1月");
        row(first, "虚构·锚点", "-1.00", "1月1日 10:14", 650);
        session.parse(first, WIDTH, HEIGHT);
        List<WechatScreenParser.TextLine> next = page(null);
        row(next, "虚构·锚点", "-1.00", "1月1日 10:14", 450);
        row(next, "虚构·去年", "-2.00", "12月31日 09:08", 650);
        WechatScreenParser.Result result = session.parse(next, WIDTH, HEIGHT);
        assertEquals("NEEDS_MONTH", result.code);
        assertTrue(result.transactions.isEmpty());
    }

    @Test public void sessionResetAndLeavingListDiscardContext() {
        WechatScreenParser.Session session = new WechatScreenParser.Session();
        session.parse(anchorPage(), WIDTH, HEIGHT);
        session.reset();
        List<WechatScreenParser.TextLine> next = page(null);
        row(next, "虚构·锚点", "-1.00", "10月2日 10:14", 450);
        assertEquals("NEEDS_MONTH", session.parse(next, WIDTH, HEIGHT).code);
        session.parse(anchorPage(), WIDTH, HEIGHT);
        assertEquals("NOT_BILL_LIST", session.parse(Collections.emptyList(), WIDTH, HEIGHT).code);
        assertEquals("NEEDS_MONTH", session.parse(next, WIDTH, HEIGHT).code);
    }

    @Test public void duplicateOcrRowsWithOnlyHanSpacesShareANewCandidateAndScrollAnchor() {
        WechatScreenParser.Session session = new WechatScreenParser.Session();
        List<WechatScreenParser.TextLine> first = page("2026年10月v");
        row(first, "虚构 早餐店", "-18.50", "10月2日 10:14", 650);
        row(first, "虚构早餐店", "-18.50", "10月2日 10:14", 850);
        WechatScreenParser.Result initial = session.parse(first, WIDTH, HEIGHT);
        assertEquals(1, initial.transactions.size());
        assertEquals("虚构早餐店", initial.transactions.get(0).counterparty);

        List<WechatScreenParser.TextLine> next = page(null);
        row(next, "虚 构\u00a0早餐店", "-18.50", "10月2日 10:14", 450);
        row(next, "虚构·新行", "-2.00", "10月1日 09:08", 650);
        WechatScreenParser.Result scrolled = session.parse(next, WIDTH, HEIGHT);
        assertEquals("OK", scrolled.code);
        assertEquals(2, scrolled.transactions.size());
        assertEquals(initial.transactions.get(0).id, scrolled.transactions.get(0).id);
        assertEquals(time(2026, 10, 1, 9, 8), scrolled.transactions.get(1).occurredAt);
    }

    @Test public void englishWordSpacesNeverBecomeEquivalentScrollAnchors() {
        WechatScreenParser.Session session = new WechatScreenParser.Session();
        List<WechatScreenParser.TextLine> first = page("2026年10月");
        row(first, "Fictional Coffee Shop", "-18.50", "10月2日 10:14", 650);
        session.parse(first, WIDTH, HEIGHT);
        List<WechatScreenParser.TextLine> next = page(null);
        row(next, "Fictional CoffeeShop", "-18.50", "10月2日 10:14", 450);
        assertEquals("NEEDS_MONTH", session.parse(next, WIDTH, HEIGHT).code);
    }

    private static List<WechatScreenParser.TextLine> anchorPage() {
        List<WechatScreenParser.TextLine> lines = page("2026年10月");
        row(lines, "虚构·锚点", "-1.00", "10月2日 10:14", 650);
        return lines;
    }

    private static WechatScreenParser.Result parse(List<WechatScreenParser.TextLine> lines) {
        return WechatScreenParser.parse(lines, WIDTH, HEIGHT);
    }

    private static List<WechatScreenParser.TextLine> page(String month) {
        List<WechatScreenParser.TextLine> lines = new ArrayList<>();
        lines.add(line("账单", 50, 150, 250, 190));
        lines.add(line("全部账单  查找交易  收支统计", 50, 300, 1030, 350));
        if (month != null) month(lines, month, 480);
        return lines;
    }

    private static void month(List<WechatScreenParser.TextLine> lines, String text, int top) {
        lines.add(line(text, 50, top, 480, top + 40));
    }

    private static void row(List<WechatScreenParser.TextLine> lines, String title, String amount, String date, int top) {
        lines.add(line(title, 210, top, 820, top + 40));
        lines.add(line(amount, 900, top, 1040, top + 40));
        lines.add(line(date, 210, top + 80, 520, top + 115));
    }

    private static WechatScreenParser.TextLine line(String text, int left, int top, int right, int bottom) {
        return new WechatScreenParser.TextLine(text, left, top, right, bottom);
    }

    private static long time(int year, int month, int day, int hour, int minute) {
        return LocalDateTime.of(year, month, day, hour, minute).atZone(ZoneId.of("Asia/Shanghai"))
                .toInstant().toEpochMilli();
    }
}
