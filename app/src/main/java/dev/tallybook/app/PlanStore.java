package dev.tallybook.app;

import android.content.Context;
import android.content.SharedPreferences;

import dev.tallybook.core.BudgetPlan;
import dev.tallybook.core.SavingsGoal;

import java.time.LocalDate;

/** Local planning data, isolated by ledger just like the transactions. */
public final class PlanStore {
    private final SharedPreferences prefs;

    public PlanStore(Context context, String source) {
        if (!LedgerStore.WECHAT.equals(source) && !LedgerStore.DEMO.equals(source)) {
            throw new IllegalArgumentException("未知账本");
        }
        prefs = context.getApplicationContext().getSharedPreferences("plans_" + source, Context.MODE_PRIVATE);
    }

    public BudgetPlan loadBudget() {
        if (!prefs.contains("budget_start")) return null;
        try {
            return new BudgetPlan(LocalDate.parse(prefs.getString("budget_start", "")),
                    LocalDate.parse(prefs.getString("budget_end", "")), prefs.getLong("budget_opening", 0),
                    prefs.getLong("budget_fixed", 0), prefs.getLong("budget_savings", 0));
        } catch (RuntimeException invalid) { throw new IllegalStateException("生活费计划暂时无法读取，请重新设置。", invalid); }
    }

    public void saveBudget(BudgetPlan plan) {
        requireSaved(prefs.edit().putString("budget_start", plan.startDate.toString())
                .putString("budget_end", plan.endInclusive.toString()).putLong("budget_opening", plan.openingMinor)
                .putLong("budget_fixed", plan.fixedReserveMinor).putLong("budget_savings", plan.savingsReserveMinor).commit());
    }

    public SavingsGoal loadGoal() {
        if (!prefs.contains("goal_name")) return null;
        try {
            return new SavingsGoal(prefs.getString("goal_name", ""), prefs.getLong("goal_target", 0),
                    prefs.getLong("goal_saved", 0), LocalDate.parse(prefs.getString("goal_date", "")));
        } catch (RuntimeException invalid) { throw new IllegalStateException("存钱目标暂时无法读取，请重新设置。", invalid); }
    }

    public void saveGoal(SavingsGoal goal) {
        requireSaved(prefs.edit().putString("goal_name", goal.name).putLong("goal_target", goal.targetMinor)
                .putLong("goal_saved", goal.savedMinor).putString("goal_date", goal.targetDate.toString()).commit());
    }

    public void clear() { requireSaved(prefs.edit().clear().commit()); }

    public void initializeDemo(LocalDate today) {
        if (!prefs.contains("budget_start")) saveBudget(new BudgetPlan(today.withDayOfMonth(1),
                today.withDayOfMonth(today.lengthOfMonth()), 180000, 25000, 20000));
        if (!prefs.contains("goal_name")) saveGoal(new SavingsGoal("去看海", 240000, 60000, today.plusDays(120)));
    }

    private static void requireSaved(boolean saved) {
        if (!saved) throw new IllegalStateException("未能保存到本机，请稍后重试。");
    }
}
