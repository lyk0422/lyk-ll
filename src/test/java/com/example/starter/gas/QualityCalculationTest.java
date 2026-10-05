package com.example.starter.gas;

import com.example.starter.gas.service.QualityCalculation;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 气质核验纯计算：组分折算、采样体积加权、绝对相对偏差与恰 1% 边界。
 */
class QualityCalculationTest {

    private static final Map<String, BigDecimal> HV_TABLE = Map.of(
            "CH4", new BigDecimal("40.000000"),
            "C2H6", new BigDecimal("44.000000"),
            "N2", BigDecimal.ZERO);

    @Test
    void sampleHeatingValue_isRatioWeightedByComponentTable() {
        // 0.9×40 + 0.1×44 = 40.4
        BigDecimal hv = QualityCalculation.sampleHeatingValue(
                Map.of("CH4", new BigDecimal("90"), "C2H6", new BigDecimal("10")), HV_TABLE);
        assertThat(hv).isEqualByComparingTo("40.400000");
    }

    @Test
    void sampleHeatingValue_unknownComponent_rejected() {
        assertThatThrownBy(() -> QualityCalculation.sampleHeatingValue(
                Map.of("H2", new BigDecimal("100")), HV_TABLE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void verifiedHeatingValue_isSampleVolumeWeighted() {
        // (100×40 + 300×40.4) / 400 = 40.3
        BigDecimal verified = QualityCalculation.verifiedHeatingValue(List.of(
                new BigDecimal[]{new BigDecimal("100"), new BigDecimal("40.000000")},
                new BigDecimal[]{new BigDecimal("300"), new BigDecimal("40.400000")}));
        assertThat(verified).isEqualByComparingTo("40.300000");
    }

    @Test
    void deviationRate_usesVoucherHeatingValueAsDenominator() {
        // |40.4 - 40| / 40 = 0.01 恰为 1%
        assertThat(QualityCalculation.deviationRate(
                new BigDecimal("40.400000"), new BigDecimal("40.000000")))
                .isEqualByComparingTo("0.010000");
        // |40.44 - 40| / 40 = 0.011 超过 1%
        assertThat(QualityCalculation.deviationRate(
                new BigDecimal("40.440000"), new BigDecimal("40.000000")))
                .isEqualByComparingTo("0.011000");
    }

    @Test
    void deviationRate_zeroVoucherHeatingValue_rejected() {
        assertThatThrownBy(() -> QualityCalculation.deviationRate(
                new BigDecimal("40.000000"), BigDecimal.ZERO))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void exactlyOnePercent_isConsistent_onlyAboveOnePercent_isQualityDiff() {
        assertThat(QualityCalculation.isQualityDiff(new BigDecimal("0.010000"))).isFalse();
        assertThat(QualityCalculation.isQualityDiff(new BigDecimal("0.009999"))).isFalse();
        assertThat(QualityCalculation.isQualityDiff(new BigDecimal("0.010001"))).isTrue();
        assertThat(QualityCalculation.isQualityDiff(new BigDecimal("0.011000"))).isTrue();
    }
}
