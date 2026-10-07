package com.example.starter.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 阶梯生成纯逻辑单测：每分钟移动不超过上限、最后恰到目标、期限恰等、到达后保持。
 */
class StaircaseEngineTest {

    @Test
    @DisplayName("1000 降到 800、上限 100kW/分钟：需 2 分钟，阶梯 900、800 恰到目标")
    void rampDownExactLanding() {
        assertThat(StaircaseEngine.neededMinutes(1000, 800, 100)).isEqualTo(2);
        assertThat(StaircaseEngine.powerAt(1000, 800, 100, 1)).isEqualTo(900);
        assertThat(StaircaseEngine.powerAt(1000, 800, 100, 2)).isEqualTo(800);
        // 到达后保持目标值。
        assertThat(StaircaseEngine.powerAt(1000, 800, 100, 3)).isEqualTo(800);
    }

    @Test
    @DisplayName("距离不被上限整除：最后一步取余量恰到目标，不越过")
    void lastStepFitsRemainder() {
        // 距离 250，上限 100：需 3 分钟，阶梯 900、800、750。
        assertThat(StaircaseEngine.neededMinutes(1000, 750, 100)).isEqualTo(3);
        assertThat(StaircaseEngine.powerAt(1000, 750, 100, 1)).isEqualTo(900);
        assertThat(StaircaseEngine.powerAt(1000, 750, 100, 2)).isEqualTo(800);
        assertThat(StaircaseEngine.powerAt(1000, 750, 100, 3)).isEqualTo(750);
        assertThat(StaircaseEngine.powerAt(1000, 750, 100, 4)).isEqualTo(750);
    }

    @Test
    @DisplayName("升功率方向对称：每分钟不超过上限，恰到目标")
    void rampUpSymmetric() {
        assertThat(StaircaseEngine.neededMinutes(500, 800, 100)).isEqualTo(3);
        assertThat(StaircaseEngine.powerAt(500, 800, 100, 1)).isEqualTo(600);
        assertThat(StaircaseEngine.powerAt(500, 800, 100, 2)).isEqualTo(700);
        assertThat(StaircaseEngine.powerAt(500, 800, 100, 3)).isEqualTo(800);
    }

    @Test
    @DisplayName("起始恰等目标：需 0 分钟，任意分钟序号均为目标值")
    void zeroDistance() {
        assertThat(StaircaseEngine.neededMinutes(800, 800, 100)).isZero();
        assertThat(StaircaseEngine.powerAt(800, 800, 100, 1)).isEqualTo(800);
    }

    @Test
    @DisplayName("期限恰等边界：距离恰为上限整数倍时所需分钟恰为该倍数")
    void deadlineExactlySufficient() {
        assertThat(StaircaseEngine.neededMinutes(1000, 800, 100)).isEqualTo(2);
        assertThat(StaircaseEngine.neededMinutes(1000, 800, 101)).isEqualTo(2);
        // 上限 99 时 2 分钟只能移动 198 < 200，需 3 分钟。
        assertThat(StaircaseEngine.neededMinutes(1000, 800, 99)).isEqualTo(3);
    }

    @Test
    @DisplayName("非法参数：非正上限或分钟序号小于 1 拒绝")
    void invalidArgumentsRejected() {
        assertThatThrownBy(() -> StaircaseEngine.neededMinutes(1000, 800, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StaircaseEngine.powerAt(1000, 800, 100, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
