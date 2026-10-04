package dev.tallybook.core;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/** Offline, explicitly hypothetical experiments. No account or ledger mutation. */
public final class LearningMath {
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final MathContext PRECISION = MathContext.DECIMAL128;
    private LearningMath() { }

    public static final class Growth {
        public final long contributedMinor, endingMinor, purchasingPowerMinor;
        private Growth(long contributed, long ending, long purchasingPower) {
            contributedMinor = contributed; endingMinor = ending; purchasingPowerMinor = purchasingPower;
        }
    }

    /** Rate is nominal annual percent divided by periods; additions occur AFTER each period's growth.
     *  Interest rounds HALF_UP to cents each period. Inflation is annual and deflates the final value. */
    public static Growth growth(long principal, long addition, BigDecimal annualPercent,
                                int years, int periodsPerYear, BigDecimal inflationPercent) {
        amount(principal); amount(addition); rate(annualPercent, false); rate(inflationPercent, true);
        if (years < 0 || years > 100 || (periodsPerYear != 1 && periodsPerYear != 12))
            throw new IllegalArgumentException("年数需为 0—100；追加频率只能按年或按月。");
        int periods = years * periodsPerYear;
        long contributed = bounded(BigDecimal.valueOf(principal)
                .add(BigDecimal.valueOf(addition).multiply(BigDecimal.valueOf(periods))));
        // Keep the integer numerator and denominator so even cent-rounding ties are exact.
        BigDecimal denominator = HUNDRED.multiply(BigDecimal.valueOf(periodsPerYear));
        BigDecimal numerator = denominator.add(annualPercent);
        long balance = principal;
        for (int i = 0; i < periods; i++) {
            BigDecimal changed = BigDecimal.valueOf(balance).multiply(numerator)
                    .divide(denominator, 0, RoundingMode.HALF_UP);
            balance = bounded(changed.add(BigDecimal.valueOf(addition)));
        }
        BigDecimal priceFactor = BigDecimal.ONE.add(inflationPercent.movePointLeft(2)).pow(years, PRECISION);
        long purchasingPower = bounded(BigDecimal.valueOf(balance).divide(priceFactor, 0, RoundingMode.HALF_UP));
        return new Growth(contributed, balance, purchasingPower);
    }

    public static final class Diversification {
        public final long concentratedMinor, oneFallsMinor, allFallMinor, largestHoldingMinor;
        private Diversification(long concentrated, long oneFalls, long allFall, long largestHolding) {
            concentratedMinor = concentrated; oneFallsMinor = oneFalls; allFallMinor = allFall;
            largestHoldingMinor = largestHolding;
        }
    }

    /** Equal-cent allocation: remainder goes to the first holdings. The first (largest) holding falls. */
    public static Diversification diversify(long principal, int holdings, BigDecimal singleLoss,
                                            BigDecimal commonLoss) {
        amount(principal); loss(singleLoss); loss(commonLoss);
        if (holdings < 1 || holdings > 1000) throw new IllegalArgumentException("项目数请填写 1—1000 的整数。");
        long largest = principal / holdings + (principal % holdings == 0 ? 0 : 1);
        return new Diversification(afterLoss(principal, singleLoss),
                principal - largest + afterLoss(largest, singleLoss), afterLoss(principal, commonLoss), largest);
    }

    public static final class Remainder {
        public final long availableMinor, shortfallMinor, illustrativeSavingMinor, illustrativeExtraRepaymentMinor;
        private Remainder(long available, long shortfall) {
            availableMinor = available; shortfallMinor = shortfall;
            illustrativeSavingMinor = available / 2;
            illustrativeExtraRepaymentMinor = available - illustrativeSavingMinor;
        }
    }

    /** Book-inspired arithmetic only: mandatory obligations are removed before splitting the remainder. */
    public static Remainder discretionary(long income, long necessary, long contractRequired) {
        amount(income); amount(necessary); amount(contractRequired);
        long remaining = Math.subtractExact(Math.subtractExact(income, necessary), contractRequired);
        return new Remainder(Math.max(0, remaining), Math.max(0, -remaining));
    }

    private static long afterLoss(long value, BigDecimal percent) {
        return bounded(BigDecimal.valueOf(value).multiply(HUNDRED.subtract(percent))
                .divide(HUNDRED, 0, RoundingMode.HALF_UP));
    }
    private static void rate(BigDecimal value, boolean inflation) {
        if (value == null || value.scale() > 6 || value.compareTo(new BigDecimal("-100")) < 0
                || (inflation && value.compareTo(new BigDecimal("-100")) == 0)
                || value.compareTo(HUNDRED) > 0)
            throw new IllegalArgumentException(inflation ? "通胀假设须大于 -100%、不高于 100%，最多六位小数。"
                    : "年变化率须在 -100% 到 100% 之间，最多六位小数。");
    }
    private static void loss(BigDecimal value) {
        rate(value, false);
        if (value.signum() < 0) throw new IllegalArgumentException("下跌幅度请填写 0—100%。");
    }
    private static void amount(long value) {
        if (value < 0 || value > Transaction.MAX_ABS_AMOUNT_MINOR)
            throw new IllegalArgumentException("金额超出支持范围。");
    }
    private static long bounded(BigDecimal value) {
        long result = value.longValueExact(); amount(result); return result;
    }
}
