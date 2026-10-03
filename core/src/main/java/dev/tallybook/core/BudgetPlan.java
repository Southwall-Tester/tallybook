package dev.tallybook.core;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/** One inclusive spending period. All money fields are CNY cents. */
public final class BudgetPlan {
    public final LocalDate startDate;
    public final LocalDate endInclusive;
    public final long openingMinor;
    public final long fixedReserveMinor;
    public final long savingsReserveMinor;

    public BudgetPlan(LocalDate startDate, LocalDate endInclusive, long openingMinor,
                      long fixedReserveMinor, long savingsReserveMinor) {
        this.startDate = Objects.requireNonNull(startDate, "startDate");
        this.endInclusive = Objects.requireNonNull(endInclusive, "endInclusive");
        long days = ChronoUnit.DAYS.between(startDate, endInclusive) + 1;
        if (days < 1 || days > 366) {
            throw new IllegalArgumentException("Budget period must contain 1 to 366 days");
        }
        this.openingMinor = money(openingMinor, "openingMinor");
        this.fixedReserveMinor = money(fixedReserveMinor, "fixedReserveMinor");
        this.savingsReserveMinor = money(savingsReserveMinor, "savingsReserveMinor");
    }

    static long money(long amount, String field) {
        if (amount < 0 || amount > Transaction.MAX_ABS_AMOUNT_MINOR) {
            throw new IllegalArgumentException("Invalid " + field);
        }
        return amount;
    }
}
