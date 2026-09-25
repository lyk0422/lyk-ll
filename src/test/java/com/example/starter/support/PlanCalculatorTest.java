package com.example.starter.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import com.example.starter.api.PlanProfileStep;
import com.example.starter.domain.DivePhase;

/**
 * 速率恰 18 合格、超速告警与减压剖面可复算的纯函数测试。
 */
class PlanCalculatorTest {

    @Test
    void rateExactlyEighteenIsLegal() {
        // 18 米 / 60 秒 = 恰 18 m/min。
        BigDecimal rate = PlanCalculator.rateMetersPerMin(DivePhase.DESCEND, 0, 18, 0, 60);
        assertThat(rate).isEqualByComparingTo("18.0000");
        assertThat(PlanCalculator.isRateAlarm(rate)).isFalse();
    }

    @Test
    void rateJustAboveEighteenAlarms() {
        // 18 米 / 50 秒 = 21.6 m/min，超速。
        BigDecimal tooFast = PlanCalculator.rateMetersPerMin(DivePhase.ASCEND, 18, 0, 0, 50);
        assertThat(tooFast).isEqualByComparingTo("21.6000");
        assertThat(PlanCalculator.isRateAlarm(tooFast)).isTrue();
    }

    @Test
    void rateJustBelowEighteenIsLegal() {
        BigDecimal rate = PlanCalculator.rateMetersPerMin(DivePhase.DESCEND, 0, 18, 0, 61);
        assertThat(rate.compareTo(BigDecimal.valueOf(18))).isLessThan(0);
        assertThat(PlanCalculator.isRateAlarm(rate)).isFalse();
    }

    @Test
    void profileIsReproducibleAtDepthThirty() {
        var steps = PlanCalculator.profile(30, 20, 20, true);
        assertThat(steps).extracting(PlanProfileStep::phase)
                .containsExactly("DESCEND", "BOTTOM", "ASCEND", "SAFETY_STOP", "ASCEND");
        PlanProfileStep bottom = steps.get(1);
        assertThat(bottom.durationSeconds()).isEqualTo(20 * 60L);
        assertThat(bottom.startDepthM()).isEqualTo(30);
    }

    @Test
    void overlapSecondsMatchesAccrualWindowMath() {
        // 台账区间 [0,21600]，窗口 [-1000, 1000) 重叠 1000 秒。
        assertThat(PlanCalculator.overlapSeconds(0, 21600, -1000, 1000)).isEqualTo(1000L);
        // 恰 6 小时整区间与 24h 窗口完全重叠。
        assertThat(PlanCalculator.overlapSeconds(0, 21600, -86400, 86400)).isEqualTo(21600L);
        // 不重叠为 0。
        assertThat(PlanCalculator.overlapSeconds(100, 200, 200, 300)).isZero();
    }

    @Test
    void fingerprintChangesWhenAnyBusinessFieldChanges() {
        String f1 = new FingerprintHasher().apply("APPLICATION", "req-1", "A", "B", 18, 56);
        String f2 = new FingerprintHasher().apply("APPLICATION", "req-1", "A", "B", 18, 57);
        String fSame = new FingerprintHasher().apply("APPLICATION", "req-1", "A", "B", 18, 56);
        assertThat(f1).isEqualTo(fSame).isNotEqualTo(f2);
    }

    /** 测试内复用 service 的规范化方式：类型|字段 依次拼接。 */
    private static final class FingerprintHasher {
        String apply(String kind, Object... fields) {
            StringBuilder sb = new StringBuilder(kind);
            for (Object field : fields) {
                sb.append('|').append(field);
            }
            return Fingerprints.sha256(sb.toString());
        }
    }
}
