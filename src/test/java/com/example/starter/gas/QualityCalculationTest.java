package com.example.starter.gas;

import com.example.starter.gas.domain.QualitySampleRow;
import com.example.starter.gas.service.QualityCalculation;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 气质组分核验纯计算：组分热值折算、同日体积加权、相对偏差与恰 1% 边界。
 */
class QualityCalculationTest {

    private static final Map<String, BigDecimal> COMPONENT_HV = Map.of(
            "CH4", new BigDecimal("40.000000"),
            "C2H6", new BigDecimal("60.000000"),
            "N2", BigDecimal.ZERO);

    @Test
    void sampleHeatingValue_weightedByComponentRatio() {
        // 100% CH4 → 40.000000
        assertThat(QualityCalculation.sampleHeatingValue(
                Map.of("CH4", new BigDecimal("100")), COMPONENT_HV))
                .isEqualByComparingTo("40.000000");
        // 98% CH4 + 2% C2H6 → (98×40 + 2×60)/100 = 40.4
        assertThat(QualityCalculation.sampleHeatingValue(
                Map.of("CH4", new BigDecimal("98"), "C2H6", new BigDecimal("2")), COMPONENT_HV))
                .isEqualByComparingTo("40.400000");
        // 含零热值组分：90% CH4 + 10% N2 → 36.0
        assertThat(QualityCalculation.sampleHeatingValue(
                Map.of("CH4", new BigDecimal("90"), "N2", new BigDecimal("10")), COMPONENT_HV))
                .isEqualByComparingTo("36.000000");
    }

    @Test
    void sampleHeatingValue_unknownComponentRejected() {
        assertThatThrownBy(() -> QualityCalculation.sampleHeatingValue(
                Map.of("H2S", new BigDecimal("100")), COMPONENT_HV))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void verifiedHeatingValue_weightedBySameDaySampleVolume() {
        // (100×40.4 + 300×40.0) / 400 = 40.1
        List<QualitySampleRow> samples = List.of(
                sample("2026-09-20T10:00:00Z", "100", "40.400000"),
                sample("2026-09-20T18:00:00Z", "300", "40.000000"));
        assertThat(QualityCalculation.verifiedHeatingValue(samples))
                .isEqualByComparingTo("40.100000");
    }

    @Test
    void relativeDeviation_exactOnePercentIsConsistent_onlyAboveOnePercentDiffers() {
        // 凭证热值 40，核验热值 40.4 → 偏差恰为 1%
        BigDecimal exact = QualityCalculation.relativeDeviation(
                new BigDecimal("40.400000"), new BigDecimal("40.000000"));
        assertThat(exact).isEqualByComparingTo("0.01000000");
        assertThat(QualityCalculation.isDifference(exact)).isFalse();

        // 核验热值 40.6 → 偏差 1.5% > 1% → 气质差异
        BigDecimal above = QualityCalculation.relativeDeviation(
                new BigDecimal("40.600000"), new BigDecimal("40.000000"));
        assertThat(above).isEqualByComparingTo("0.01500000");
        assertThat(QualityCalculation.isDifference(above)).isTrue();

        // 低于 1% 一致；负偏差取绝对值
        BigDecimal below = QualityCalculation.relativeDeviation(
                new BigDecimal("39.700000"), new BigDecimal("40.000000"));
        assertThat(below).isEqualByComparingTo("0.00750000");
        assertThat(QualityCalculation.isDifference(below)).isFalse();
    }

    @Test
    void relativeDeviation_zeroVoucherHeatingValueRejected() {
        assertThatThrownBy(() -> QualityCalculation.relativeDeviation(
                new BigDecimal("40.000000"), BigDecimal.ZERO))
                .isInstanceOf(IllegalStateException.class);
    }

    private static QualitySampleRow sample(String time, String volume, String hv) {
        return new QualitySampleRow(1L, "ST01", Instant.parse(time),
                LocalDate.of(2026, 9, 20), new BigDecimal(volume), new BigDecimal(hv),
                "{}", "req");
    }
}
