package dev.tallybook.core;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Book-inspired local practice. No method moves money or modifies a ledger. */
public final class MoneyCoach {
    public static final int SCHEMA_VERSION = 1;
    public static final int MAX_STATE_BYTES = 8 * 1024 * 1024;
    public static final int MAX_RECORDS = 20_000;
    public static final long ACTION_WINDOW_MILLIS = 72L * 60 * 60 * 1000;
    public static final long MAX_AMOUNT_MINOR = Transaction.MAX_ABS_AMOUNT_MINOR;
    private static final LocalDate MIN_DATE = LocalDate.of(2000, 1, 1);
    private static final LocalDate MAX_DATE = LocalDate.of(2100, 1, 1);
    private MoneyCoach() { }

    public static final List<Theme> THEMES = Collections.unmodifiableList(Arrays.asList(
            new Theme(0, "友好亲和", "尊重他人，保持礼貌，也承认自己可能有不了解的地方。", "今天可以怎样倾听或友好回应一个人？"),
            new Theme(1, "勇于承担", "分清能影响的事，为自己的选择负责，并在需要时寻求帮助。", "面对一件困难，我能做的一个小行动是什么？"),
            new Theme(2, "善待他人", "留意别人的优点，以尊重的方式表达不同意见。", "今天看到了谁的一个具体优点？"),
            new Theme(3, "帮助给予", "在自己的能力与边界内提供帮助，给予也可以是时间和关心。", "我愿意为谁提供什么小帮助？"),
            new Theme(4, "感恩之心", "留意平常容易忽略的人与事，记录具体的感谢。", "今天有什么具体经历值得感谢？"),
            new Theme(5, "勤学不辍", "保持好奇，通过阅读、实践和向他人请教继续学习。", "今天学到什么，准备怎样试着使用？"),
            new Theme(6, "值得信赖", "认真对待约定；做不到时及时说明，并调整到可承担的行动。", "今天准备履行或重新协商哪个小约定？")));

    /** Largest remainders receive spare cents; equal remainders use goose, dream, daily order. */
    public static Allocation allocate(long amountMinor, int goosePercent, int dreamPercent, int dailyPercent) {
        amount(amountMinor, true);
        ratios(goosePercent, dreamPercent, dailyPercent);
        int[] percentages = {goosePercent, dreamPercent, dailyPercent};
        long[] parts = new long[3], remainders = new long[3];
        long assigned = 0;
        for (int i = 0; i < 3; i++) {
            long product = Math.multiplyExact(amountMinor, percentages[i]);
            parts[i] = product / 100;
            remainders[i] = product % 100;
            assigned += parts[i];
        }
        for (long left = amountMinor - assigned; left > 0; left--) {
            int best = 0;
            for (int i = 1; i < 3; i++) if (remainders[i] > remainders[best]) best = i;
            parts[best]++;
            remainders[best] = -1;
        }
        return new Allocation(parts[0], parts[1], parts[2]);
    }

    /** Exactly 72 elapsed hours, independent of timezone and daylight-saving changes. */
    public static long deadlineAfter72Hours(long createdAt) {
        time(createdAt);
        long result = Math.addExact(createdAt, ACTION_WINDOW_MILLIS);
        time(result);
        return result;
    }

    public static Theme themeFor(LocalDate start, LocalDate day) {
        date(start);
        date(day);
        if (day.isBefore(start)) throw invalid("练习日期不能早于开始日期");
        return THEMES.get((int) (ChronoUnit.DAYS.between(start, day) % 7));
    }

    public static LocalDate weekStart(LocalDate day) {
        date(day);
        return date(day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)));
    }

    /** Teaching approximation: pass 12 for 12%, not 0.12. */
    public static double ruleOf72Years(double annualPercent) {
        if (!Double.isFinite(annualPercent) || annualPercent <= 0 || annualPercent > 100) {
            throw invalid("72 法则需要大于 0 且不超过 100 的假设年百分率");
        }
        return 72.0 / annualPercent;
    }

    /** Hypothetical annual compounding, rounding to cents at each year end. */
    public static long compoundMinor(long principalMinor, BigDecimal annualPercent, int years) {
        amount(principalMinor, true);
        if (annualPercent == null || annualPercent.compareTo(BigDecimal.valueOf(-100)) < 0
                || annualPercent.compareTo(BigDecimal.valueOf(100)) > 0
                || annualPercent.scale() > 6 || years < 0 || years > 100) {
            throw invalid("请检查假设年利率和模拟年数");
        }
        BigDecimal factor = BigDecimal.ONE.add(annualPercent.movePointLeft(2));
        BigDecimal value = BigDecimal.valueOf(principalMinor);
        for (int i = 0; i < years; i++) {
            value = value.multiply(factor).setScale(0, RoundingMode.HALF_UP);
            if (value.compareTo(BigDecimal.valueOf(MAX_AMOUNT_MINOR)) > 0) throw invalid("模拟结果超出可表示的金额范围");
        }
        return value.longValueExact();
    }

    public static final class Theme {
        public final int index;
        public final String title, explanation, prompt;
        private Theme(int index, String title, String explanation, String prompt) {
            this.index = index; this.title = title; this.explanation = explanation; this.prompt = prompt;
        }
    }

    public static final class Allocation {
        public final long gooseMinor, dreamMinor, dailyMinor;
        private Allocation(long gooseMinor, long dreamMinor, long dailyMinor) {
            this.gooseMinor = gooseMinor; this.dreamMinor = dreamMinor; this.dailyMinor = dailyMinor;
        }
        public long totalMinor() { return gooseMinor + dreamMinor + dailyMinor; }
    }

    /** A draft may have zero amounts and no targetDate; an existing image URI survives edits. */
    public static final class Wish {
        public final int slot;
        public final String title, reason, imageUri;
        public final boolean priority;
        public final long targetMinor, savedMinor;
        public final LocalDate targetDate;
        public Wish(int slot, String title, String reason, boolean priority, long targetMinor,
                    long savedMinor, LocalDate targetDate, String imageUri) {
            if (slot < 0 || slot >= 10) throw invalid("愿望位置应为 0 到 9");
            this.slot = slot;
            this.title = text(title, 256, !priority, false);
            this.reason = text(reason, 4000, true, true);
            amount(targetMinor, true); amount(savedMinor, true);
            if (targetDate != null) date(targetDate);
            this.imageUri = text(imageUri, 2048, true, false);
            if (!imageUri.isEmpty() && !imageUri.startsWith("content://")) throw invalid("梦想图片只能引用本机选择的内容");
            this.priority = priority; this.targetMinor = targetMinor;
            this.savedMinor = savedMinor; this.targetDate = targetDate;
        }
        public static Wish empty(int slot) { return new Wish(slot, "", "", false, 0, 0, null, ""); }
        public long remainingMinor() { return Math.max(0, targetMinor - savedMinor); }
    }

    /** An executed record is a user's assertion, never an actual bank transfer. */
    public static final class AllocationRecord {
        public final String id, note;
        public final long amountMinor, createdAt, executedAt;
        public final int goosePercent, dreamPercent, dailyPercent;
        public final Allocation allocation;
        public AllocationRecord(String id, long amountMinor, int goosePercent, int dreamPercent,
                                int dailyPercent, long createdAt, long executedAt, String note) {
            this.id = id(id); amount(amountMinor, false);
            this.allocation = allocate(amountMinor, goosePercent, dreamPercent, dailyPercent);
            time(createdAt); completion(createdAt, executedAt);
            this.amountMinor = amountMinor; this.goosePercent = goosePercent;
            this.dreamPercent = dreamPercent; this.dailyPercent = dailyPercent;
            this.createdAt = createdAt; this.executedAt = executedAt;
            this.note = text(note, 4000, true, true);
        }
        public boolean isExecuted() { return executedAt != 0; }
    }

    public static final class SuccessEntry {
        public final LocalDate date;
        public final List<String> items;
        public SuccessEntry(LocalDate date, List<String> items) {
            this.date = date(date);
            if (items == null || items.size() > 100) throw invalid("每天可保留最多 100 条成功记录");
            ArrayList<String> copy = new ArrayList<>();
            for (String item : items) copy.add(text(item, 4000, true, true));
            this.items = Collections.unmodifiableList(copy);
        }
    }

    public static final class Action {
        public final String id, title, obstacle;
        public final long createdAt, deadlineAt, completedAt;
        public Action(String id, String title, long createdAt, long completedAt, String obstacle) {
            this.id = id(id); this.title = text(title, 512, false, false);
            this.deadlineAt = deadlineAfter72Hours(createdAt);
            completion(createdAt, completedAt);
            this.createdAt = createdAt; this.completedAt = completedAt;
            this.obstacle = text(obstacle, 4000, true, true);
        }
        public String status(long now) {
            time(now);
            if (completedAt != 0) return "DONE";
            return now >= deadlineAt ? "OVERDUE" : "OPEN";
        }
        public Action complete(long when) {
            if (when == 0) throw invalid("请填写有效的完成时间");
            return new Action(id, title, createdAt, when, obstacle);
        }
    }

    public static final class WeeklyReview {
        public final LocalDate weekStart;
        public final String progress, obstacle, nextStep;
        public WeeklyReview(LocalDate weekStart, String progress, String obstacle, String nextStep) {
            this.weekStart = date(weekStart);
            if (weekStart.getDayOfWeek() != DayOfWeek.MONDAY) throw invalid("周复盘需使用该周周一的日期");
            this.progress = text(progress, 8000, true, true);
            this.obstacle = text(obstacle, 8000, true, true);
            this.nextStep = text(nextStep, 8000, true, true);
        }
    }

    public static final class PracticeEntry {
        public final LocalDate date;
        public final int themeIndex;
        public final String understanding, experience;
        public PracticeEntry(LocalDate date, int themeIndex, String understanding, String experience) {
            this.date = date(date);
            if (themeIndex < 0 || themeIndex >= THEMES.size()) throw invalid("未知练习主题");
            this.themeIndex = themeIndex;
            this.understanding = text(understanding, 8000, true, true);
            this.experience = text(experience, 8000, true, true);
        }
    }

    /** Immutable snapshot. Every collection is copied and identity duplicates are rejected. */
    public static final class State {
        public final LocalDate practiceStart;
        public final int goosePercent, dreamPercent, dailyPercent;
        public final List<Wish> wishes;
        public final List<AllocationRecord> allocations;
        public final List<SuccessEntry> successes;
        public final List<Action> actions;
        public final List<WeeklyReview> reviews;
        public final List<PracticeEntry> practices;
        public State(LocalDate practiceStart, int goosePercent, int dreamPercent, int dailyPercent,
                     List<Wish> wishes, List<AllocationRecord> allocations, List<SuccessEntry> successes,
                     List<Action> actions, List<WeeklyReview> reviews, List<PracticeEntry> practices) {
            this.practiceStart = date(practiceStart); ratios(goosePercent, dreamPercent, dailyPercent);
            this.goosePercent = goosePercent; this.dreamPercent = dreamPercent; this.dailyPercent = dailyPercent;
            this.wishes = normalizeWishes(wishes); this.allocations = copy(allocations);
            this.successes = copy(successes); this.actions = copy(actions);
            this.reviews = copy(reviews); this.practices = copy(practices);
            Set<String> keys = new HashSet<>();
            for (AllocationRecord record : this.allocations) unique(keys, record.id);
            keys.clear();
            for (SuccessEntry entry : this.successes) unique(keys, entry.date.toString());
            keys.clear();
            for (Action action : this.actions) unique(keys, action.id);
            keys.clear();
            for (WeeklyReview review : this.reviews) unique(keys, review.weekStart.toString());
            keys.clear();
            for (PracticeEntry entry : this.practices) unique(keys, entry.date.toString());
        }
        public static State empty(LocalDate start) {
            return new State(start, 50, 40, 10, Collections.emptyList(), Collections.emptyList(),
                    Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
        }
        public String toJson() { return MoneyCoachJson.write(this); }
        public static State fromJson(String json) { return MoneyCoachJson.read(json); }
    }

    private static List<Wish> normalizeWishes(List<Wish> wishes) {
        if (wishes == null || wishes.size() > 10) throw invalid("最多保存 10 个愿望");
        Wish[] slots = new Wish[10];
        int priorities = 0;
        for (Wish wish : wishes) {
            if (wish == null || slots[wish.slot] != null) throw invalid("愿望位置不能重复");
            slots[wish.slot] = wish;
            if (wish.priority) priorities++;
        }
        if (priorities > 3) throw invalid("最多选择 3 个当前重点梦想");
        for (int i = 0; i < slots.length; i++) if (slots[i] == null) slots[i] = Wish.empty(i);
        return Collections.unmodifiableList(Arrays.asList(slots));
    }
    private static <T> List<T> copy(List<T> source) {
        if (source == null || source.size() > MAX_RECORDS) throw invalid("练习记录数量超出支持范围");
        ArrayList<T> result = new ArrayList<>(source);
        for (T value : result) if (value == null) throw invalid("练习记录不能为空");
        return Collections.unmodifiableList(result);
    }
    private static void unique(Set<String> keys, String key) {
        if (!keys.add(key)) throw invalid("同一条练习记录不能重复保存");
    }
    static void ratios(int goose, int dream, int daily) {
        if (goose < 0 || goose > 100 || dream < 0 || dream > 100 || daily < 0 || daily > 100
                || goose + dream + daily != 100) throw invalid("三项比例需在 0 到 100 之间，合计 100%");
    }
    private static String id(String id) {
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,128}")) throw invalid("练习记录编号无效");
        return id;
    }
    private static void amount(long amount, boolean zeroAllowed) {
        if (amount < (zeroAllowed ? 0 : 1) || amount > MAX_AMOUNT_MINOR) throw invalid("请输入有效的非负金额");
    }
    private static void time(long time) {
        if (time < Transaction.MIN_OCCURRED_AT || time >= Transaction.MAX_OCCURRED_AT) throw invalid("时间须在 2000 至 2099 年之间");
    }
    private static void completion(long createdAt, long completedAt) {
        if (completedAt != 0) {
            time(completedAt);
            if (completedAt < createdAt) throw invalid("完成时间不能早于创建时间");
        }
    }
    private static LocalDate date(LocalDate date) {
        if (date == null || date.isBefore(MIN_DATE) || !date.isBefore(MAX_DATE)) throw invalid("日期须在 2000 至 2099 年之间");
        return date;
    }
    private static String text(String text, int maxLength, boolean emptyAllowed, boolean multiline) {
        if (text == null || text.length() > maxLength || (!emptyAllowed && text.trim().isEmpty())) throw invalid("请检查文字是否为空或过长");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isISOControl(c) && !(multiline && (c == '\n' || c == '\r' || c == '\t'))) throw invalid("文字含有无法保存的控制字符");
        }
        return text;
    }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
