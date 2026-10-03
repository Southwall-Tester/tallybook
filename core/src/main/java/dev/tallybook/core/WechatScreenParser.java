package dev.tallybook.core;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Conservative parser for OCR lines from a visible WeChat bill list.
 * The caller must additionally check the foreground package and an explicit capture session.
 * This is not a detail/API parser: list records have no order ID and always require review.
 */
public final class WechatScreenParser {
    private static final ZoneId LOCAL_ZONE = ZoneId.of("Asia/Shanghai");
    // The month selector's dropdown can be included in the same OCR line as the date.
    // Only one known arrow is allowed; unrelated trailing text is not silently discarded.
    private static final Pattern MONTH = Pattern.compile("^([0-9]{4})年([0-9]{1,2})月[vV▼∨]?$");
    private static final Pattern DATE = Pattern.compile("^([0-9]{1,2})月([0-9]{1,2})日([0-9]{2})[:：]([0-9]{2})$");
    private static final Pattern AMOUNT = Pattern.compile("[+-](?:0|[1-9][0-9]{0,11})(?:\\.[0-9]{1,2})?");

    private WechatScreenParser() {}

    /** Coordinates are pixels in the same upright image supplied to OCR. */
    public static final class TextLine {
        public final String text;
        public final int left, top, right, bottom;

        public TextLine(String text, int left, int top, int right, int bottom) {
            this.text = text;
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }

        private double centerY() { return (top + bottom) / 2.0; }
    }

    public static final class Result {
        public final List<Transaction> transactions;
        public final int skippedRows;
        public final String code;

        private Result(List<Transaction> transactions, int skippedRows, String code) {
            this.transactions = Collections.unmodifiableList(new ArrayList<>(transactions));
            this.skippedRows = skippedRows;
            this.code = code;
        }
    }

    /** Parse one independent frame; never infer a year from the device clock. */
    public static Result parse(List<TextLine> lines, int width, int height) {
        return parseFrame(lines, width, height, Collections.emptyMap()).result;
    }

    /**
     * Session-local scroll continuity. A year can cross a frame boundary only through an
     * identical visible title + signed amount + month/day/minute row in both adjacent frames.
     * An unseen month never inherits the year of a different month. Reset on start/stop,
     * leaving WeChat, filter/navigation changes, or loss of foreground-list verification.
     */
    public static final class Session {
        private Map<String, Integer> previousRows = Collections.emptyMap();

        public synchronized void reset() { previousRows = Collections.emptyMap(); }

        public synchronized Result parse(List<TextLine> lines, int width, int height) {
            Frame frame = parseFrame(lines, width, height, previousRows);
            previousRows = frame.anchors;
            return frame.result;
        }
    }

    private static Frame parseFrame(List<TextLine> source, int width, int height,
                                    Map<String, Integer> previous) {
        if (source == null || source.size() > 2048 || width <= 0 || height <= 0
                || width > 20000 || height > 20000) return empty("NOT_BILL_LIST", 0);
        List<TextLine> lines = cleanLines(source, width, height);
        int bodyTop = listBodyTop(lines, width, height);
        if (bodyTop < 0) return empty("NOT_BILL_LIST", 0);

        List<MonthMarker> months = new ArrayList<>();
        List<TextLine> amountLines = new ArrayList<>();
        for (TextLine line : lines) {
            if (line.top < bodyTop) continue;
            Matcher month = MONTH.matcher(compact(line.text));
            if (line.left < width * .45 && month.matches()) {
                int year = Integer.parseInt(month.group(1));
                int number = Integer.parseInt(month.group(2));
                MonthMarker marker = new MonthMarker(line.top, line.bottom, year, number);
                if (!months.isEmpty()) {
                    MonthMarker prior = months.get(months.size() - 1);
                    if (line.top < prior.bottom) {
                        months.remove(months.size() - 1);
                        marker = new MonthMarker(Math.min(prior.top, line.top), Math.max(prior.bottom, line.bottom),
                                prior.year == year && prior.month == number ? year : 0,
                                prior.year == year && prior.month == number ? number : 0);
                    }
                }
                months.add(marker);
            } else if (line.left >= width * .62 && line.bottom - line.top <= width * .12
                    && looksLikeAmount(line.text)) {
                amountLines.add(line);
            }
        }

        List<Row> rows = new ArrayList<>();
        int skipped = 0;
        for (TextLine amountLine : amountLines) {
            Row row = readRow(lines, amountLines, months, amountLine, width, bodyTop);
            if (row == null) skipped++;
            else rows.add(row);
        }

        // Only exact overlaps in this frame may lend context to other rows of that month.
        Map<Integer, Integer> inheritedYears = new HashMap<>();
        Set<Integer> conflictingMonths = new HashSet<>();
        for (Row row : rows) {
            Integer year = previous.get(row.anchor());
            if (year == null || year < 2000) continue;
            Integer prior = inheritedYears.put(row.month, year);
            if (prior != null && !prior.equals(year)) conflictingMonths.add(row.month);
        }
        for (Integer month : conflictingMonths) inheritedYears.remove(month);

        // With no header, every visible row's month must have an independently matched anchor.
        // In particular a new January cannot silently receive December's previous year.
        if (months.isEmpty() && (rows.isEmpty() || rows.stream()
                .anyMatch(row -> !inheritedYears.containsKey(row.month)))) {
            return empty("NEEDS_MONTH", skipped + rows.size());
        }

        Map<String, Transaction> transactions = new LinkedHashMap<>();
        Map<String, Integer> anchors = new HashMap<>();
        boolean missingMonth = false;
        for (Row row : rows) {
            MonthMarker marker = null;
            for (MonthMarker candidate : months) {
                if (candidate.bottom <= row.top) marker = candidate;
            }
            Integer year;
            if (marker == null) {
                year = inheritedYears.get(row.month);
                if (year == null) { skipped++; missingMonth = true; continue; }
            } else {
                if (!marker.valid() || marker.month != row.month) { skipped++; continue; }
                year = marker.year;
            }
            try {
                long time = LocalDateTime.of(year, row.month, row.day, row.hour, row.minute)
                        .atZone(LOCAL_ZONE).toInstant().toEpochMilli();
                Transaction transaction = Transaction.screen(row.title, row.amount, time);
                transactions.putIfAbsent(transaction.id, transaction);
                Integer priorYear = anchors.put(row.anchor(), year);
                if (priorYear != null && !priorYear.equals(year)) anchors.put(row.anchor(), -1);
            } catch (DateTimeException | IllegalArgumentException rejected) {
                skipped++;
            }
        }
        String code = transactions.isEmpty() ? (missingMonth ? "NEEDS_MONTH" : "NO_READABLE_ROWS") : "OK";
        return new Frame(new Result(new ArrayList<>(transactions.values()), skipped, code), anchors);
    }

    private static List<TextLine> cleanLines(List<TextLine> source, int width, int height) {
        List<TextLine> lines = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (TextLine line : source) {
            if (line == null || line.text == null || line.text.length() > 1024
                    || line.left < 0 || line.top < 0 || line.right > width || line.bottom > height
                    || line.right <= line.left || line.bottom <= line.top) continue;
            String text = line.text.trim();
            if (text.isEmpty() || text.chars().anyMatch(Character::isISOControl)) continue;
            String key = line.left + ":" + line.top + ":" + line.right + ":" + line.bottom + ":" + text;
            if (seen.add(key)) lines.add(new TextLine(text, line.left, line.top, line.right, line.bottom));
        }
        lines.sort(Comparator.comparingInt((TextLine line) -> line.top).thenComparingInt(line -> line.left));
        return lines;
    }

    private static int listBodyTop(List<TextLine> lines, int width, int height) {
        TextLine title = null;
        for (TextLine line : lines) {
            String text = compact(line.text);
            if (text.equals("发送") || text.equals("按住说话") || text.equals("聊天信息")) return -1;
            if (text.equals("账单") && line.top < height * .18 && line.bottom - line.top < width * .15) {
                if (title != null) return -1;
                title = line;
            }
        }
        if (title == null) return -1;
        int controlBottom = -1;
        for (TextLine line : lines) {
            String text = compact(line.text);
            if (line.top >= title.bottom && line.top - title.bottom <= width * .30
                    && (text.contains("全部账单") || text.contains("查找交易"))) {
                controlBottom = Math.max(controlBottom, line.bottom);
            }
        }
        return controlBottom;
    }

    private static Row readRow(List<TextLine> lines, List<TextLine> amounts, List<MonthMarker> months,
                               TextLine amountLine, int width, int bodyTop) {
        Long amount = signedAmount(amountLine.text);
        if (amount == null) return null;
        List<TextLine> titles = new ArrayList<>();
        for (TextLine line : lines) {
            if (line.top < bodyTop || line.left < width * .12 || line.left > width * .60
                    || line.right >= amountLine.left - width * .01 || !sameRow(line, amountLine, width)
                    || reserved(line.text)) continue;
            titles.add(line);
        }
        if (titles.size() != 1) return null;
        TextLine title = titles.get(0);
        double rowEnd = Math.max(title.bottom, amountLine.bottom) + width * .18;
        for (TextLine next : amounts) {
            if (next.centerY() > amountLine.centerY() + width * .035) rowEnd = Math.min(rowEnd, next.top);
            else if (next != amountLine && sameRow(next, amountLine, width)) return null;
        }
        for (MonthMarker month : months) {
            if (month.top > amountLine.centerY()) rowEnd = Math.min(rowEnd, month.top);
        }
        List<Matcher> dates = new ArrayList<>();
        for (TextLine line : lines) {
            if (line.top < title.bottom - width * .01 || line.bottom > rowEnd
                    || Math.abs(line.left - title.left) > width * .12 || line.right > width * .72) continue;
            Matcher date = DATE.matcher(compact(line.text));
            if (date.matches()) dates.add(date);
        }
        if (dates.size() != 1) return null;
        Matcher date = dates.get(0);
        return new Row(Transaction.normalizeScreenTitle(title.text), amount, title.top, Integer.parseInt(date.group(1)),
                Integer.parseInt(date.group(2)), Integer.parseInt(date.group(3)), Integer.parseInt(date.group(4)));
    }

    private static boolean sameRow(TextLine a, TextLine b, int width) {
        return Math.abs(a.centerY() - b.centerY())
                <= Math.max(a.bottom - a.top, b.bottom - b.top) * .65 + width * .005;
    }

    private static boolean reserved(String text) {
        String compact = compact(text);
        return MONTH.matcher(compact).matches() || DATE.matcher(compact).matches()
                || compact.startsWith("支出") || compact.startsWith("收入")
                || compact.contains("全部账单") || compact.contains("查找交易")
                || compact.equals("收支统计") || compact.equals("账单")
                || (looksLikeAmount(text) && compact.matches("[+\\-−＋－¥￥0-9OoIl.,]+"));
    }

    private static boolean looksLikeAmount(String text) {
        if (text.length() > 64) return false;
        char first = text.charAt(0);
        return (first == '+' || first == '-' || first == '−' || first == '＋' || first == '－'
                || first == '¥' || first == '￥' || Character.isDigit(first))
                && text.chars().anyMatch(Character::isDigit);
    }

    private static Long signedAmount(String text) {
        // U+2212 is a mathematical minus, not an inferred sign or an OCR digit correction.
        String normalized = text.charAt(0) == '−' ? "-" + text.substring(1) : text;
        if (!AMOUNT.matcher(normalized).matches()) return null;
        try {
            long amount = new BigDecimal(normalized).movePointRight(2).longValueExact();
            return amount == 0 || Math.abs(amount) > Transaction.MAX_ABS_AMOUNT_MINOR ? null : amount;
        } catch (ArithmeticException rejected) { return null; }
    }

    private static String compact(String text) { return text.replaceAll("[\\s\\u00a0]", ""); }

    private static Frame empty(String code, int skipped) {
        return new Frame(new Result(Collections.emptyList(), skipped, code), Collections.emptyMap());
    }

    private static final class Frame {
        final Result result;
        final Map<String, Integer> anchors;
        Frame(Result result, Map<String, Integer> anchors) { this.result = result; this.anchors = anchors; }
    }

    private static final class MonthMarker {
        final int top, bottom, year, month;
        MonthMarker(int top, int bottom, int year, int month) {
            this.top = top; this.bottom = bottom; this.year = year; this.month = month;
        }
        boolean valid() { return year >= 2000 && year < 2100 && month >= 1 && month <= 12; }
    }

    private static final class Row {
        final String title;
        final long amount;
        final int top, month, day, hour, minute;
        Row(String title, long amount, int top, int month, int day, int hour, int minute) {
            this.title = title; this.amount = amount; this.top = top; this.month = month;
            this.day = day; this.hour = hour; this.minute = minute;
        }
        String anchor() { return title.length() + ":" + title + ":" + amount + ":" + month + ":"
                + day + ":" + hour + ":" + minute; }
    }
}
