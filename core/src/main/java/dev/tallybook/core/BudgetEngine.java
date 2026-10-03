package dev.tallybook.core;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;

/** Computes a plan from the caller's current, already deduplicated ledger. */
public final class BudgetEngine {
    private BudgetEngine() { }

    public static Snapshot summarize(BudgetPlan plan, List<Transaction> transactions,
                                     LocalDate today, ZoneId zone) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(transactions, "transactions");
        Objects.requireNonNull(today, "today");
        Objects.requireNonNull(zone, "zone");
        long income = 0;
        long expense = 0;
        int pending = 0;
        for (Transaction transaction : transactions) {
            Objects.requireNonNull(transaction, "transaction");
            LocalDate date = Instant.ofEpochMilli(transaction.occurredAt).atZone(zone).toLocalDate();
            if (date.isBefore(plan.startDate) || date.isAfter(plan.endInclusive) || date.isAfter(today)) {
                continue;
            }
            if (transaction.reviewRequired) {
                pending = Math.addExact(pending, 1);
            } else if (transaction.amountMinor > 0) {
                income = Math.addExact(income, transaction.amountMinor);
            } else {
                expense = Math.addExact(expense, Math.negateExact(transaction.amountMinor));
            }
        }
        long remaining = Math.addExact(plan.openingMinor, income);
        remaining = Math.subtractExact(remaining, expense);
        remaining = Math.subtractExact(remaining, plan.fixedReserveMinor);
        remaining = Math.subtractExact(remaining, plan.savingsReserveMinor);
        boolean active = !today.isBefore(plan.startDate) && !today.isAfter(plan.endInclusive);
        int daysLeft = active ? Math.toIntExact(ChronoUnit.DAYS.between(today, plan.endInclusive) + 1) : 0;
        long daily = active ? Math.max(0, remaining) / daysLeft : 0;
        return new Snapshot(income, expense, remaining, daily, daysLeft, pending, active);
    }

    public static final class Snapshot {
        public final long incomeMinor;
        public final long expenseMinor;
        public final long remainingMinor;
        public final long dailyMinor;
        public final int daysLeft;
        public final int pendingCount;
        public final boolean active;

        private Snapshot(long incomeMinor, long expenseMinor, long remainingMinor, long dailyMinor,
                         int daysLeft, int pendingCount, boolean active) {
            this.incomeMinor = incomeMinor;
            this.expenseMinor = expenseMinor;
            this.remainingMinor = remainingMinor;
            this.dailyMinor = dailyMinor;
            this.daysLeft = daysLeft;
            this.pendingCount = pendingCount;
            this.active = active;
        }
    }
}
