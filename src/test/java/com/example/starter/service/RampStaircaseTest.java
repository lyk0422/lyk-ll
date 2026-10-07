package com.example.starter.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.example.starter.web.ApiUnprocessableException;

/**
 * 受控分段阶梯生成纯逻辑单测：期限恰等、提前到达保持、上行阶梯、期限内不可达。
 */
class RampStaircaseTest {

    @Test
    @DisplayName("期限恰等：1000 降到 800、上限 100 kW/分钟，恰需两分钟，阶梯 900→800")
    void exactDurationDownRamp() {
        List<Integer> steps = RampStaircase.build(1000, 800, 100, 2);
        assertThat(steps).containsExactly(900, 800);
        assertThat(RampStaircase.reachable(1000, 800, 100, 2)).isTrue();
    }

    @Test
    @DisplayName("期限差一分钟即不可达：1000→800 上限 100 只有 1 分钟，拒绝")
    void oneMinuteShortIsInfeasible() {
        assertThat(RampStaircase.reachable(1000, 800, 100, 1)).isFalse();
        assertThatThrownBy(() -> RampStaircase.build(1000, 800, 100, 1))
                .isInstanceOf(ApiUnprocessableException.class)
                .hasMessageContaining("无法到达目标");
    }

    @Test
    @DisplayName("上行阶梯：800 升到 1000、上限 100，恰需两分钟，阶梯 900→1000")
    void exactDurationUpRamp() {
        assertThat(RampStaircase.build(800, 1000, 100, 2)).containsExactly(900, 1000);
    }

    @Test
    @DisplayName("期限宽限：提前恰达目标后保持目标值，最后一分钟仍恰为目标")
    void reachesEarlyThenHoldsTarget() {
        assertThat(RampStaircase.build(1000, 800, 100, 4))
                .containsExactly(900, 800, 800, 800);
    }

    @Test
    @DisplayName("尾分钟按剩余距离移动：1500→800 上限 500，阶梯 1000→800，第三步保持")
    void lastStepMovesOnlyRemainingDistance() {
        assertThat(RampStaircase.build(1500, 800, 500, 3))
                .containsExactly(1000, 800, 800);
    }

    @Test
    @DisplayName("起始即目标：整段保持目标值")
    void startEqualsTargetHoldsFlat() {
        assertThat(RampStaircase.build(800, 800, 100, 2)).containsExactly(800, 800);
        assertThat(RampStaircase.reachable(800, 800, 100, 1)).isTrue();
    }
}
