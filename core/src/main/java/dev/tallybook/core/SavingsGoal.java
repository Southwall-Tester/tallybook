package dev.tallybook.core;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/** User-maintained goal progress, independent of budget reserves and ledger transactions. */
public final class SavingsGoal {
    public final String name;
    public final long targetMinor;
    public final long savedMinor;
    public final LocalDate targetDate;

    public SavingsGoal(String name, long targetMinor, long savedMinor, LocalDate targetDate) {
        if (name == null || name.isEmpty() || name.length() > 128 || !name.equals(name.trim())) {
            throw new IllegalArgumentException("Invalid goal name");
        }
        for (int i = 0; i < name.length(); i++) {
            if (Character.isISOControl(name.charAt(i))) throw new IllegalArgumentException("Invalid goal name");
        }
        this.name = name;
        this.targetMinor = BudgetPlan.money(targetMinor, "targetMinor");
        if (targetMinor == 0) throw new IllegalArgumentException("Goal target must be positive");
        this.savedMinor = BudgetPlan.money(savedMinor, "savedMinor");
        this.targetDate = Objects.requireNonNull(targetDate, "targetDate");
    }

    public long remainingMinor() {
        return Math.max(0, targetMinor - savedMinor);
    }

    public int progressPercent() {
        return savedMinor >= targetMinor ? 100 : (int) (savedMinor * 100 / targetMinor);
    }

    /** Includes today and rounds up to cents; an overdue goal needs all remaining money. */
    public long dailyNeedMinor(LocalDate today) {
        Objects.requireNonNull(today, "today");
        long remaining = remainingMinor();
        if (remaining == 0 || today.isAfter(targetDate)) return remaining;
        long days = ChronoUnit.DAYS.between(today, targetDate) + 1;
        return remaining / days + (remaining % days == 0 ? 0 : 1);
    }
}
