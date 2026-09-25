package com.example.starter.metro;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 票价计算器边界测试：起步价 2 元含 4 公里，超出每 5 公里加 1 元，
 * 不足 5 公里按 5 公里计，恰满 5 公里按加价档计。
 */
class FareCalculatorTest {

    private final FareCalculator calculator = new FareCalculator();

    @Test
    void zeroDistanceIsFree() {
        assertEquals(0L, calculator.fareCents(0L));
    }

    @Test
    void baseFareCoversFourKilometers() {
        assertEquals(200L, calculator.fareCents(1L));
        assertEquals(200L, calculator.fareCents(4000L));
    }

    @Test
    void oneMeterBeyondBaseAddsOneStep() {
        assertEquals(300L, calculator.fareCents(4001L));
    }

    @Test
    void exactFiveKilometerStepCountsAsFullStep() {
        // 恰满 5 公里按加价档计：4+5=9 公里应收 3 元
        assertEquals(300L, calculator.fareCents(9000L));
    }

    @Test
    void oneMeterBeyondFullStepAddsNextStep() {
        assertEquals(400L, calculator.fareCents(9001L));
        assertEquals(400L, calculator.fareCents(14000L));
    }

    @Test
    void networkMaxDistanceFare() {
        // 24 公里：2 + ceil(20/5) = 6 元
        assertEquals(600L, calculator.fareCents(24000L));
    }
}
