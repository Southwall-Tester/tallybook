package dev.tallybook.core;

import org.junit.Test;
import java.math.BigDecimal;
import static org.junit.Assert.*;

public final class LearningMathTest {
    private static BigDecimal rate(String value) { return new BigDecimal(value); }
    @Test public void originalAnnualExampleAndInflation() {
        LearningMath.Growth r = LearningMath.growth(300000, 0, rate("3"), 2, 1, rate("3"));
        assertEquals(318270, r.endingMinor); assertEquals(300000, r.purchasingPowerMinor);
    }
    @Test public void yearEndAdditionDoesNotEarnBeforeArrival() {
        LearningMath.Growth r = LearningMath.growth(10000, 10000, rate("10"), 2, 1, rate("0"));
        assertEquals(33100, r.endingMinor); assertEquals(30000, r.contributedMinor);
    }
    @Test public void monthlyZeroGrowthConservesContributions() {
        LearningMath.Growth r = LearningMath.growth(1, 7, rate("0"), 2, 12, rate("0"));
        assertEquals(169, r.endingMinor); assertEquals(169, r.contributedMinor);
    }
    @Test public void monthlyNominalRateAndCentRoundingAreExplicit() {
        assertEquals(112684, LearningMath.growth(100000, 0, rate("12"), 1, 12, rate("0")).endingMinor);
        assertEquals(2, LearningMath.growth(1, 0, rate("50"), 1, 1, rate("0")).endingMinor);
    }
    @Test public void negativeGrowthAndCompleteLossStillAllowLaterContribution() {
        assertEquals(270750, LearningMath.growth(300000, 0, rate("-5"), 2, 1, rate("0")).endingMinor);
        assertEquals(100, LearningMath.growth(300000, 100, rate("-100"), 2, 1, rate("0")).endingMinor);
    }
    @Test public void zeroYearsHasNoContributionOrInflation() {
        LearningMath.Growth r = LearningMath.growth(123, 99, rate("100"), 0, 12, rate("100"));
        assertEquals(123, r.endingMinor); assertEquals(123, r.contributedMinor); assertEquals(123, r.purchasingPowerMinor);
    }
    @Test public void oneOfTwentyFallsVersusCommonFall() {
        LearningMath.Diversification r = LearningMath.diversify(2000000, 20, rate("40"), rate("40"));
        assertEquals(1200000, r.concentratedMinor); assertEquals(1960000, r.oneFallsMinor); assertEquals(1200000, r.allFallMinor);
    }
    @Test public void CentRemainderAndSingleHoldingDoNotInventMoney() {
        LearningMath.Diversification r = LearningMath.diversify(3, 2, rate("100"), rate("100"));
        assertEquals(2, r.largestHoldingMinor); assertEquals(1, r.oneFallsMinor); assertEquals(0, r.allFallMinor);
        assertEquals(60, LearningMath.diversify(100, 1, rate("40"), rate("0")).oneFallsMinor);
    }
    @Test public void obligationsBeforeHalfSplitAndShortfall() {
        LearningMath.Remainder r = LearningMath.discretionary(10001, 5000, 2000);
        assertEquals(3001, r.availableMinor); assertEquals(1500, r.illustrativeSavingMinor); assertEquals(1501, r.illustrativeExtraRepaymentMinor);
        LearningMath.Remainder deficit = LearningMath.discretionary(100, 90, 20);
        assertEquals(10, deficit.shortfallMinor); assertEquals(0, deficit.availableMinor);
    }
    @Test public void rejectsInvalidRatesDatesAndOverflow() {
        assertThrows(IllegalArgumentException.class, () -> LearningMath.growth(0, 0, rate("0"), 1, 12, rate("-100")));
        assertThrows(IllegalArgumentException.class, () -> LearningMath.growth(-1, 0, rate("0"), 1, 12, rate("0")));
        assertThrows(IllegalArgumentException.class, () -> LearningMath.growth(0, 0, rate("0"), 101, 12, rate("0")));
        assertThrows(IllegalArgumentException.class, () -> LearningMath.growth(0, 0, rate("0"), 1, 2, rate("0")));
        assertThrows(IllegalArgumentException.class, () -> LearningMath.growth(Transaction.MAX_ABS_AMOUNT_MINOR, 1, rate("0"), 1, 1, rate("0")));
        assertThrows(IllegalArgumentException.class, () -> LearningMath.diversify(1, 0, rate("0"), rate("0")));
        assertThrows(IllegalArgumentException.class, () -> LearningMath.diversify(1, 1, rate("-1"), rate("0")));
    }
}
