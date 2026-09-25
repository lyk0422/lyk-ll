package com.example.starter.dye;

import com.example.starter.dye.service.ColorMatchService;
import com.example.starter.dye.web.dto.ColorMatchRequest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 对色 CIE76 与门限边界纯单元测试：恰 0.8 合格、0.8 以上拒绝。
 */
class ColorMatchMathTest {

    private ColorMatchRequest req(double sL, double sA, double sB) {
        return new ColorMatchRequest("req", "S1",
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.valueOf(sL), BigDecimal.valueOf(sA), BigDecimal.valueOf(sB));
    }

    @Test
    void exactlyZeroPointEightPasses() {
        // 单轴色差恰为 0.8
        BigDecimal delta = ColorMatchService.cie76(req(0.8, 0, 0));
        assertThat(delta).isEqualByComparingTo("0.8000");
        assertThat(delta.compareTo(ColorMatchService.PASS_THRESHOLD)).isEqualTo(0);
        assertThat(delta.compareTo(ColorMatchService.PASS_THRESHOLD) <= 0).isTrue();
    }

    @Test
    void justAboveZeroPointEightFails() {
        BigDecimal delta = ColorMatchService.cie76(req(0.8001, 0, 0));
        assertThat(delta.compareTo(ColorMatchService.PASS_THRESHOLD)).isGreaterThan(0);
    }

    @Test
    void cie76CombinesThreeAxes() {
        // sqrt(0.4^2+0.3^2+0)=0.5
        BigDecimal delta = ColorMatchService.cie76(req(0.4, 0.3, 0));
        assertThat(delta).isEqualByComparingTo("0.5000");
    }

    @Test
    void identicalColorIsZero() {
        BigDecimal delta = ColorMatchService.cie76(req(0, 0, 0));
        assertThat(delta).isEqualByComparingTo("0.0000");
    }
}
