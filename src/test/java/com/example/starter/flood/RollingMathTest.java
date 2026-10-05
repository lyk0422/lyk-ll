package com.example.starter.flood;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.starter.flood.domain.ReleaseJudgment;
import com.example.starter.flood.service.RollingMath;
import com.example.starter.flood.service.RollingMath.HydroPoint;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 滚动演算纯计算单元测试：过程线取值（恰等点/插值/端点持平）、剩余段峰值需求、
 * 泄流能力恰等临界、预计蓄水下限 0。
 */
class RollingMathTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 9, 25, 10, 0);

    private static List<HydroPoint> hydrograph() {
        return List.of(
                new HydroPoint(T0, new BigDecimal("10.000")),
                new HydroPoint(T0.plusMinutes(30), new BigDecimal("20.000")),
                new HydroPoint(T0.plusMinutes(60), new BigDecimal("40.000")),
                new HydroPoint(T0.plusMinutes(90), new BigDecimal("15.000")));
    }

    @Test
    @DisplayName("过程线取值：恰等点取该点，区间中点线性插值")
    void rateAtExactAndInterpolated() {
        assertThat(RollingMath.rateAt(hydrograph(), T0.plusMinutes(30)))
                .isEqualByComparingTo("20.000");
        // 10:45 位于 20 与 40 中点 → 30
        assertThat(RollingMath.rateAt(hydrograph(), T0.plusMinutes(45)))
                .isEqualByComparingTo("30.000");
    }

    @Test
    @DisplayName("过程线取值：超出范围按端点值持平")
    void rateAtEndpointHold() {
        assertThat(RollingMath.rateAt(hydrograph(), T0.minusMinutes(15)))
                .isEqualByComparingTo("10.000");
        assertThat(RollingMath.rateAt(hydrograph(), T0.plusMinutes(120)))
                .isEqualByComparingTo("15.000");
    }

    @Test
    @DisplayName("空过程线拒绝演算")
    void rateAtEmptyRejected() {
        assertThatThrownBy(() -> RollingMath.rateAt(List.of(), T0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("过程线为空");
    }

    @Test
    @DisplayName("泄流需求=剩余段峰值；基准时刻之后无点时取基准时刻流量")
    void requiredReleasePeakOfRemaining() {
        // 10:00 起剩余段峰值 40
        assertThat(RollingMath.requiredRelease(hydrograph(), T0))
                .isEqualByComparingTo("40.000");
        // 10:45 之后剩余点 40(11:00)、15(11:30) → 峰值 40
        assertThat(RollingMath.requiredRelease(hydrograph(), T0.plusMinutes(45)))
                .isEqualByComparingTo("40.000");
        // 11:30 之后无点 → 取基准时刻流量（端点持平 15）
        assertThat(RollingMath.requiredRelease(hydrograph(), T0.plusMinutes(90)))
                .isEqualByComparingTo("15.000");
    }

    @Test
    @DisplayName("泄流能力判定：大于充足、恰等临界、小于不足")
    void releaseJudgmentBoundary() {
        assertThat(RollingMath.releaseJudgment(new BigDecimal("40.001"),
                new BigDecimal("40.000"))).isEqualTo(ReleaseJudgment.SUFFICIENT);
        assertThat(RollingMath.releaseJudgment(new BigDecimal("40.000"),
                new BigDecimal("40.000"))).isEqualTo(ReleaseJudgment.CRITICAL);
        assertThat(RollingMath.releaseJudgment(new BigDecimal("39.999"),
                new BigDecimal("40.000"))).isEqualTo(ReleaseJudgment.INSUFFICIENT);
    }

    @Test
    @DisplayName("预计蓄水=当前+时段入库-时段泄流；泄流透支库存时下限 0")
    void projectedStorageFloorZero() {
        // 1000 + 10*1800 - 5*1800 = 10000
        assertThat(RollingMath.projectedStorage(new BigDecimal("1000"),
                new BigDecimal("10"), new BigDecimal("5")))
                .isEqualByComparingTo("10000.0000");
        // 1000 + 0 - 20*1800 < 0 → 0
        assertThat(RollingMath.projectedStorage(new BigDecimal("1000"),
                BigDecimal.ZERO, new BigDecimal("20")))
                .isEqualByComparingTo("0.0000");
    }
}
