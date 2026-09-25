package com.example.starter.transition;

import com.example.starter.transition.api.InspectionRequest;
import com.example.starter.transition.domain.SwitchMode;
import com.example.starter.transition.po.Grade;
import com.example.starter.transition.service.RollService;
import com.example.starter.transition.service.TransitionService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 方式判定、过渡料长度与合成正品门槛的纯规则测试（不依赖数据库）。
 */
class TransitionRuleTest {

    @Test
    void weightDiffExactlyTenPercentAllowsDirectSwitch() {
        assertEquals(SwitchMode.DIRECT,
                TransitionService.decideSwitchMode(new BigDecimal("100.00"), new BigDecimal("110.00")));
        assertEquals(SwitchMode.DIRECT,
                TransitionService.decideSwitchMode(new BigDecimal("100.00"), new BigDecimal("90.00")));
    }

    @Test
    void weightDiffAboveTenPercentRequiresStopClean() {
        assertEquals(SwitchMode.STOP_CLEAN,
                TransitionService.decideSwitchMode(new BigDecimal("100.00"), new BigDecimal("110.01")));
        assertEquals(SwitchMode.STOP_CLEAN,
                TransitionService.decideSwitchMode(new BigDecimal("80.00"), new BigDecimal("100.00")));
    }

    @Test
    void transitionLengthEqualsSpeedTimesSecondsDividedBySixty() {
        assertEquals(new BigDecimal("1350.000"),
                TransitionService.computeTransitionLength(new BigDecimal("900"), 90));
        assertEquals(new BigDecimal("300.000"),
                TransitionService.computeTransitionLength(new BigDecimal("600"), 30));
    }

    @Test
    void gateBoundaryValuesAreInclusive() {
        Grade target = targetGrade();
        // 恰达门槛：抗张=32、克重恰 +5%、灰分恰差 1 级 → 合格
        assertTrue(RollService.passesGate(
                new InspectionRequest("req-g1", "R-X",
                        new BigDecimal("115.50"), 6, new BigDecimal("32.00")), target));
        // 抗张略低于门槛 → 不合格
        assertFalse(RollService.passesGate(
                new InspectionRequest("req-g2", "R-X",
                        new BigDecimal("115.50"), 6, new BigDecimal("31.99")), target));
        // 克重偏差超 5% → 不合格
        assertFalse(RollService.passesGate(
                new InspectionRequest("req-g3", "R-X",
                        new BigDecimal("115.51"), 6, new BigDecimal("32.00")), target));
        // 灰分差 2 级 → 不合格
        assertFalse(RollService.passesGate(
                new InspectionRequest("req-g4", "R-X",
                        new BigDecimal("115.50"), 7, new BigDecimal("32.00")), target));
    }

    private Grade targetGrade() {
        Grade g = new Grade();
        g.gradeCode = "G-TARGET";
        g.basisWeight = new BigDecimal("110.00");
        g.ashLevel = 5;
        g.speedMin = new BigDecimal("900");
        g.speedMax = new BigDecimal("1100");
        g.consistencyMin = new BigDecimal("3.2");
        g.consistencyMax = new BigDecimal("4.0");
        g.tensileMin = new BigDecimal("32.00");
        return g;
    }
}
