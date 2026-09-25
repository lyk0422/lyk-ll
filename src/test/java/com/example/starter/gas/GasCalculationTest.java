package com.example.starter.gas;

import com.example.starter.gas.domain.MeterReadingRow;
import com.example.starter.gas.service.GasCalculation;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 能量折算与 3% 偏差考核纯计算单元测试。
 */
class GasCalculationTest {

    private MeterReadingRow reading(String requestId, String volume, String heatingValue) {
        return new MeterReadingRow(1L, requestId, "ST01", Instant.parse("2026-09-20T12:00:00Z"),
                LocalDate.of(2026, 9, 20), new BigDecimal(volume), new BigDecimal(heatingValue),
                Instant.now());
    }

    @Test
    void weightedHeatingValue_isVolumeWeighted() {
        // (6000×40 + 4000×38) / 10000 = 39.2
        BigDecimal weighted = GasCalculation.weightedHeatingValue(
                List.of(reading("r1", "6000", "40.000000"), reading("r2", "4000", "38.000000")));
        assertThat(weighted).isEqualByComparingTo("39.200000");
    }

    @Test
    void energy_isVolumeTimesHeatingValueRoundedToTwoDecimals() {
        BigDecimal energy = GasCalculation.energy(new BigDecimal("10000"), new BigDecimal("39.200000"));
        assertThat(energy).isEqualByComparingTo("392000.00");
        assertThat(energy.scale()).isEqualTo(2);
    }

    @Test
    void energy_roundsHalfUp() {
        // 333.33 × 0.123456 = 41.1515... 四舍五入 41.15
        BigDecimal energy = GasCalculation.energy(new BigDecimal("333.33"), new BigDecimal("0.123456"));
        assertThat(energy).isEqualByComparingTo("41.15");
    }

    @Test
    void deviationExactlyAtThreePercent_isExempt() {
        BigDecimal nominated = new BigDecimal("10000");
        BigDecimal tolerance = GasCalculation.toleranceEnergy(nominated);
        assertThat(tolerance).isEqualByComparingTo("300.00");

        BigDecimal deviationAbs = new BigDecimal("300.00");
        assertThat(deviationAbs.compareTo(tolerance) <= 0).isTrue();
        assertThat(GasCalculation.penalty(deviationAbs, tolerance, new BigDecimal("0.5")))
                .isEqualByComparingTo("0.00");
    }

    @Test
    void deviationJustOverThreePercent_chargesOnlyExcess() {
        BigDecimal tolerance = new BigDecimal("300.00");
        // 超出 1 个能量单位：(400-300)×0.5 = 50
        BigDecimal penalty = GasCalculation.penalty(new BigDecimal("400.00"), tolerance,
                new BigDecimal("0.5"));
        assertThat(penalty).isEqualByComparingTo("50.00");
    }

    @Test
    void negativeDeviationUsesAbsoluteValue() {
        BigDecimal actual = new BigDecimal("9599.00");
        BigDecimal nominated = new BigDecimal("10000.00");
        BigDecimal deviation = GasCalculation.deviation(actual, nominated);
        assertThat(deviation).isEqualByComparingTo("-401.00");
        BigDecimal tolerance = GasCalculation.toleranceEnergy(nominated);
        BigDecimal penalty = GasCalculation.penalty(deviation.abs(), tolerance, new BigDecimal("0.5"));
        // (401-300)×0.5 = 50.50
        assertThat(penalty).isEqualByComparingTo("50.50");
    }
}
