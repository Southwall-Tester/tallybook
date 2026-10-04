package dev.tallybook.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import dev.tallybook.core.MoneyCoach;
import dev.tallybook.core.MonthlyReview;
import org.json.JSONObject;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Arrays;
import java.util.Collections;

/** Fictional checks for the disposable-emulator runner only; never run on a user's phone. */
public final class MoneyCoachChecks {
    private static int checks;
    private MoneyCoachChecks() { }

    public static int run(Context context) throws Exception {
        if (!(Build.FINGERPRINT.startsWith("generic") || Build.MODEL.contains("sdk_gphone")
                || Build.MODEL.contains("Android SDK"))) {
            throw new IllegalStateException("MoneyCoach checks require a disposable emulator");
        }
        checks = 0;
        SharedPreferences realPrefs = context.getSharedPreferences("money_coach_wechat", Context.MODE_PRIVATE);
        SharedPreferences demoPrefs = context.getSharedPreferences("money_coach_demo", Context.MODE_PRIVATE);
        check(realPrefs.edit().clear().commit() && demoPrefs.edit().clear().commit(), "fixture storage reset");
        long now = System.currentTimeMillis();
        LocalDate today = LocalDate.now();
        LocalDate monday = MoneyCoach.weekStart(today);
        try (LedgerStore ledger = new LedgerStore(context)) {
            int realTransactions = ledger.list(LedgerStore.WECHAT).size();
            int demoTransactions = ledger.list(LedgerStore.DEMO).size();
            MoneyCoachStore real = new MoneyCoachStore(context, LedgerStore.WECHAT);
            MoneyCoachStore demo = new MoneyCoachStore(context, LedgerStore.DEMO);
            MoneyCoach.State empty = real.load();
            check(empty.wishes.size() == 10 && empty.successes.isEmpty() && empty.actions.isEmpty(), "empty drafts have no invented history");
            check(empty.practiceStart.equals(monday), "persistent cycle starts on Monday");
            String initial = realPrefs.getString("state", "");
            check(real.load().practiceStart.equals(monday) && initial.equals(realPrefs.getString("state", "")), "opening again does not reset cycle");
            boolean sourceRejected = false;
            try { new MoneyCoachStore(context, "other"); } catch (IllegalArgumentException expected) { sourceRejected = true; }
            check(sourceRejected, "unknown source rejected");

            real.saveWish(new MoneyCoach.Wish(0, "虚构旅行目标", "看更多地方", true, 10000, 12000, today.plusDays(90), "content://fiction/goal"));
            real.saveWish(new MoneyCoach.Wish(1, "未完成草稿", "", false, 0, 0, null, ""));
            real.saveRatios(60, 30, 10);
            real.saveAllocation(new MoneyCoach.AllocationRecord("fiction-allocation", 101, 60, 30, 10, now, 0, "仅计划"));
            real.saveSuccess(new MoneyCoach.SuccessEntry(today, Arrays.asList("完成一个小步骤", "")));
            real.saveAction(new MoneyCoach.Action("fiction-action", "列一次需求清单", now, 0, "还缺资料"));
            real.saveReview(new MoneyCoach.WeeklyReview(monday, "保存一个好做法", "缺少信息", "先问一个问题"));
            int theme = today.getDayOfWeek().getValue() - 1;
            real.savePractice(new MoneyCoach.PracticeEntry(today, theme, "自己的解释", "真实经历的虚构测试"));
            real.savePractice(new MoneyCoach.PracticeEntry(today.minusDays(7), theme, "上周解释", "上周另一经历"));

            // Same identities are intentionally present in the other space to detect cross-source updates.
            demo.saveWish(new MoneyCoach.Wish(0, "另一空间虚构目标", "", true, 50000, 100, null, ""));
            demo.saveAction(new MoneyCoach.Action("fiction-action", "演示空间行动", now, 0, ""));
            demo.saveSuccess(new MoneyCoach.SuccessEntry(today, Collections.singletonList("演示空间日记")));
            String demoSnapshot = demoPrefs.getString("state", "");
            MoneyCoach.State loaded = new MoneyCoachStore(context, LedgerStore.WECHAT).load();
            check(loaded.goosePercent == 60 && loaded.dreamPercent == 30 && loaded.dailyPercent == 10, "custom percentages persist");
            check(loaded.wishes.get(0).savedMinor == 12000 && loaded.wishes.get(0).remainingMinor() == 0
                    && loaded.wishes.get(0).imageUri.equals("content://fiction/goal") && loaded.wishes.get(1).targetDate == null, "goal and picture URI including drafts roundtrip");
            check(loaded.allocations.size() == 1 && loaded.allocations.get(0).allocation.totalMinor() == 101
                    && !loaded.allocations.get(0).isExecuted(), "planned allocation roundtrip conserves cents");
            check(loaded.successes.size() == 1 && loaded.successes.get(0).items.size() == 2, "incomplete success diary roundtrip");
            check(loaded.actions.size() == 1 && loaded.actions.get(0).deadlineAt == now + MoneyCoach.ACTION_WINDOW_MILLIS
                    && loaded.actions.get(0).obstacle.equals("还缺资料"), "action deadline and obstacle roundtrip");
            check(loaded.reviews.size() == 1 && loaded.reviews.get(0).nextStep.equals("先问一个问题"), "three-question review roundtrip");
            check(loaded.practices.size() == 2 && loaded.practices.get(0).themeIndex == theme
                    && !loaded.practices.get(0).experience.equals(loaded.practices.get(1).experience), "recurring theme retains separate weekly experiences");
            check(demo.load().wishes.get(0).title.equals("另一空间虚构目标"), "real and demo wishlist isolation");

            real.saveSuccess(new MoneyCoach.SuccessEntry(today, Arrays.asList("一", "二", "三", "四", "五", "六")));
            check(real.load().successes.size() == 1 && real.load().successes.get(0).items.size() == 6, "same diary date updates and allows more than five");
            real.saveAction(new MoneyCoach.Action("fiction-action", "更新为一小步", now, 0, "已补充资料"));
            real.completeAction("fiction-action", now + 1000);
            real.completeAction("fiction-action", now + 2000);
            check(real.load().actions.size() == 1 && real.load().actions.get(0).completedAt == now + 1000
                    && real.load().actions.get(0).obstacle.equals("已补充资料"), "completion idempotent and does not lose edits");
            real.saveAllocation(new MoneyCoach.AllocationRecord("fiction-allocation", 101, 60, 30, 10, now, now + 1000, "用户自行记已分配"));
            check(real.load().allocations.size() == 1 && real.load().allocations.get(0).isExecuted(), "plan execution updates stable ID only");
            real.saveReview(new MoneyCoach.WeeklyReview(monday, "修改后的做法", "", ""));
            check(real.load().reviews.size() == 1 && real.load().reviews.get(0).progress.equals("修改后的做法"), "weekly review updates in place");
            real.savePractice(new MoneyCoach.PracticeEntry(today, theme, "新解释", "新经历"));
            check(real.load().practices.size() == 2 && real.load().practices.get(0).understanding.equals("新解释"), "theme edit preserves other dates");

            String beforeInvalid = realPrefs.getString("state", "");
            boolean invalidRatio = false;
            try { real.saveRatios(50, 40, 20); } catch (IllegalArgumentException expected) { invalidRatio = true; }
            check(invalidRatio && beforeInvalid.equals(realPrefs.getString("state", "")), "invalid plan cannot overwrite prior state");
            real.deleteAllocation("fiction-allocation");
            real.deleteAction("fiction-action");
            real.deleteSuccess(today);
            real.deleteReview(monday);
            real.deletePractice(today);
            real.saveWish(MoneyCoach.Wish.empty(0));
            loaded = real.load();
            check(loaded.allocations.isEmpty() && loaded.actions.isEmpty() && loaded.successes.isEmpty()
                    && loaded.reviews.isEmpty() && loaded.practices.size() == 1 && loaded.wishes.get(0).title.isEmpty(), "single-item deletion and wish clearing");
            check(demoSnapshot.equals(demoPrefs.getString("state", "")), "updates completions and deletions never touch demo");
            monthlyChecks(context);
            check(ledger.list(LedgerStore.WECHAT).size() == realTransactions
                    && ledger.list(LedgerStore.DEMO).size() == demoTransactions, "practice does not create or delete ledger transactions");

            String valid = realPrefs.getString("state", "");
            String corrupt = "{broken-but-preserve-this}";
            check(realPrefs.edit().putString("state", corrupt).commit(), "inject malformed fixture");
            check(rejectsLoad(real) && rejectsSave(real) && corrupt.equals(realPrefs.getString("state", "")), "malformed JSON preserved and all writes blocked");
            check(realPrefs.edit().putInt("state", 123).commit(), "inject wrong preference type");
            check(rejectsLoad(real) && rejectsSave(real) && realPrefs.getInt("state", 0) == 123, "wrong preference type preserved and writes blocked");
            String wrongJsonType = new JSONObject(valid).put("goosePercent", "60").toString();
            check(realPrefs.edit().putString("state", wrongJsonType).commit(), "inject wrong JSON type");
            check(rejectsLoad(real) && rejectsSave(real) && wrongJsonType.equals(realPrefs.getString("state", "")), "numeric strings cannot bypass persisted type validation");
            check(demoSnapshot.equals(demoPrefs.getString("state", "")), "corruption handling isolated to selected space");
        } finally {
            if (!realPrefs.edit().clear().commit() || !demoPrefs.edit().clear().commit()) {
                throw new IllegalStateException("Cannot remove fictional coach fixtures");
            }
        }
        return checks;
    }

    private static void monthlyChecks(Context context) {
        SharedPreferences realPrefs = context.getSharedPreferences("monthly_review_wechat", Context.MODE_PRIVATE);
        SharedPreferences demoPrefs = context.getSharedPreferences("monthly_review_demo", Context.MODE_PRIVATE);
        check(realPrefs.edit().clear().commit() && demoPrefs.edit().clear().commit(), "monthly fixture reset");
        YearMonth month = YearMonth.of(2037, 2);
        MonthlyReviewStore real = new MonthlyReviewStore(context, LedgerStore.WECHAT);
        MonthlyReviewStore demo = new MonthlyReviewStore(context, LedgerStore.DEMO);
        try {
            check(real.load().isEmpty() && demo.load().isEmpty(), "monthly empty stores contain no invented entries");
            boolean unknown = false;
            try { new MonthlyReviewStore(context, "unknown"); } catch (IllegalArgumentException expected) { unknown = true; }
            check(unknown, "monthly invalid scope rejected");
            real.save(new MonthlyReview(month, "虚构核对事实", "学到的做法", "下月一小步"));
            demo.save(new MonthlyReview(month, "演示空间事实", "", ""));
            String demoSnapshot = demoPrefs.getString("state", "");
            MonthlyReview reloaded = new MonthlyReviewStore(context, LedgerStore.WECHAT).load().get(0);
            check(reloaded.month.equals(month) && reloaded.facts.equals("虚构核对事实")
                    && reloaded.nextStep.equals("下月一小步"), "monthly new store instance reloads committed record");
            real.save(new MonthlyReview(month, "更新后的事实", "", ""));
            real.save(new MonthlyReview(month.plusMonths(1), "另一个月", "", ""));
            check(real.load().size() == 2 && real.load().get(0).month.equals(month.plusMonths(1))
                    && real.load().get(1).facts.equals("更新后的事实"), "monthly same month replaces and newest month sorts first");
            real.delete(month);
            check(real.load().size() == 1 && demoSnapshot.equals(demoPrefs.getString("state", "")), "monthly single deletion isolated from other months and demo");
            String corrupt = "{preserve-corrupt-month}";
            check(realPrefs.edit().putString("state", corrupt).commit(), "inject malformed monthly fixture");
            check(rejectsMonth(() -> real.load()) && rejectsMonth(() -> real.save(new MonthlyReview(month, "", "", "")))
                    && rejectsMonth(() -> real.delete(month)) && corrupt.equals(realPrefs.getString("state", "")), "monthly malformed state blocks all writes without resetting");
            check(realPrefs.edit().putInt("state", 123).commit(), "inject wrong monthly preference type");
            check(rejectsMonth(() -> real.load()) && rejectsMonth(() -> real.save(new MonthlyReview(month, "", "", "")))
                    && rejectsMonth(() -> real.delete(month)) && realPrefs.getInt("state", 0) == 123, "monthly wrong preference type blocks writes unchanged");
            String future = "{\"schemaVersion\":2,\"records\":[]}";
            check(realPrefs.edit().putString("state", future).commit(), "inject future monthly schema");
            check(rejectsMonth(() -> real.load()) && rejectsMonth(() -> real.delete(month))
                    && future.equals(realPrefs.getString("state", "")) && demoSnapshot.equals(demoPrefs.getString("state", "")), "monthly unknown schema preserved and corruption stays isolated");
        } finally {
            if (!realPrefs.edit().clear().commit() || !demoPrefs.edit().clear().commit())
                throw new IllegalStateException("Cannot remove fictional monthly fixtures");
        }
    }

    private static boolean rejectsMonth(Runnable action) {
        try { action.run(); return false; } catch (IllegalStateException expected) { return true; }
    }

    private static boolean rejectsLoad(MoneyCoachStore store) {
        try { store.load(); return false; } catch (IllegalStateException expected) { return true; }
    }
    private static boolean rejectsSave(MoneyCoachStore store) {
        try { store.saveRatios(50, 40, 10); return false; } catch (IllegalStateException expected) { return true; }
    }
    private static void check(boolean condition, String name) {
        if (!condition) throw new IllegalStateException("Money coach check failed: " + name);
        checks++;
    }
}
