package com.example.starter.flood;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.starter.flood.service.StorageCurveCalculator;
import com.example.starter.flood.service.StorageCurveCalculator.CurvePoint;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 库容曲线线性插值单测：曲线上点精确取值、区间内插值、越界拒绝。
 */
class StorageCurveCalculatorTest {

    private final StorageCurveCalculator calculator = new StorageCurveCalculator();

    private final List<CurvePoint> curve = List.of(
            new CurvePoint(new BigDecimal("180"), new BigDecimal("0")),
            new CurvePoint(new BigDecimal("190"), new BigDecimal("300000")),
            new CurvePoint(new BigDecimal("200"), new BigDecimal("600000")),
            new CurvePoint(new BigDecimal("210"), new BigDecimal("1000000")));

    @Test
    @DisplayName("水位恰为曲线上点：取该点蓄水量，不乱序输入影响结果")
    void exactPoint() {
        assertThat(calculator.storageAt(curve, new BigDecimal("200")))
                .isEqualByComparingTo("600000.0000");
        List<CurvePoint> shuffled = List.of(curve.get(3), curve.get(0), curve.get(2),
                curve.get(1));
        assertThat(calculator.storageAt(shuffled, new BigDecimal("190")))
                .isEqualByComparingTo("300000.0000");
    }

    @Test
    @DisplayName("水位在区间内：线性插值（205m -> 800000 m3）")
    void interpolation() {
        assertThat(calculator.storageAt(curve, new BigDecimal("205")))
                .isEqualByComparingTo("800000.0000");
    }

    @Test
    @DisplayName("水位超出曲线范围：拒绝反查，避免外延失真")
    void outOfRange() {
        assertThatThrownBy(() -> calculator.storageAt(curve, new BigDecimal("211")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> calculator.storageAt(curve, new BigDecimal("179")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
