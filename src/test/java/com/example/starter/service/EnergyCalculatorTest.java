package com.example.starter.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.example.starter.persistence.MeterReadingPO;

/**
 * 能量折算纯计算单测：体积＝末表底-首表底，加权平均热值按体积增量加权，能量两位小数。
 */
class EnergyCalculatorTest {

    private MeterReadingPO reading(String time, String index, String hv) {
        return new MeterReadingPO(null, "ST1", "M1", Instant.parse(time), new BigDecimal(index),
                new BigDecimal(hv), Instant.parse("2026-09-10T00:00:00Z"), false);
    }

    @Test
    void volumeIsLastIndexMinusFirst() {
        List<MeterReadingPO> readings = List.of(
                reading("2026-09-10T00:00:00Z", "1000.000", "38.0000"),
                reading("2026-09-10T12:00:00Z", "1100.000", "39.0000"),
                reading("2026-09-10T23:00:00Z", "1200.000", "40.0000"));

        assertThat(EnergyCalculator.dayVolume(readings)).isEqualByComparingTo("200.000");
    }

    @Test
    void weightedHeatingValueUsesVolumeIncrements() {
        List<MeterReadingPO> readings = List.of(
                reading("2026-09-10T00:00:00Z", "1000.000", "38.0000"),
                reading("2026-09-10T12:00:00Z", "1100.000", "39.0000"),
                reading("2026-09-10T23:00:00Z", "1200.000", "40.0000"));

        // (100*39 + 100*40) / 200 = 39.5
        assertThat(EnergyCalculator.weightedHeatingValue(readings))
                .isEqualByComparingTo("39.5000");
    }

    @Test
    void energyIsVolumeTimesHeatingValueRoundedHalfUp() {
        // 200 * 39.5 = 7900.00
        assertThat(EnergyCalculator.energy(new BigDecimal("200.000"), new BigDecimal("39.5000")))
                .isEqualByComparingTo("7900.00");
        // 舍入验证：3.335 * 1 = 3.34（HALF_UP）
        assertThat(EnergyCalculator.energy(new BigDecimal("1"), new BigDecimal("3.335")))
                .isEqualByComparingTo("3.34");
    }

    @Test
    void rejectsFewerThanTwoReadings() {
        assertThatThrownBy(() -> EnergyCalculator.dayVolume(
                List.of(reading("2026-09-10T00:00:00Z", "1000", "38"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNonMonotonicIndex() {
        List<MeterReadingPO> readings = List.of(
                reading("2026-09-10T00:00:00Z", "1100.000", "38.0000"),
                reading("2026-09-10T12:00:00Z", "1000.000", "39.0000"));
        assertThatThrownBy(() -> EnergyCalculator.weightedHeatingValue(readings))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
