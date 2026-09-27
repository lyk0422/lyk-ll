package com.example.starter.support;

import com.example.starter.domain.PointsRules;
import com.example.starter.domain.PointsRules.PointsRejectedException;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 反复潜水积分纯规则单元测试（虚构模型，不启动 Spring、不连库）。
 */
class PointsRulesUnitTest {

    private static final Instant ANCHOR = Instant.parse("2026-09-25T00:00:00Z");

    @Test
    void firstDivePointsAreZero_whenNoAnchor() {
        assertEquals(0, PointsRules.currentPoints(40, null, ANCHOR.plusSeconds(3600)));
        assertEquals(0, PointsRules.currentPoints(0, null, ANCHOR));
    }

    @Test
    void surfaceDecay_everyFullThirtyMinutesSubtractsFive_floorZero() {
        // 0 分钟：未消减
        assertEquals(40, PointsRules.currentPoints(40, ANCHOR, ANCHOR));
        // 29 分钟：不满一个步长，不减
        assertEquals(40, PointsRules.currentPoints(40, ANCHOR, ANCHOR.plusSeconds(60 * 29)));
        // 恰 30 分钟：减 5
        assertEquals(35, PointsRules.currentPoints(40, ANCHOR, ANCHOR.plusSeconds(60 * 30)));
        // 59 分钟：仍只一个步长
        assertEquals(35, PointsRules.currentPoints(40, ANCHOR, ANCHOR.plusSeconds(60 * 59)));
        // 恰 60 分钟：两个步长减 10
        assertEquals(30, PointsRules.currentPoints(40, ANCHOR, ANCHOR.plusSeconds(60 * 60)));
        // 90 分钟：减 15
        assertEquals(25, PointsRules.currentPoints(40, ANCHOR, ANCHOR.plusSeconds(60 * 90)));
        // 远超归零：40 分需要 8 步=240 分钟，300 分钟后归零不越负
        assertEquals(0, PointsRules.currentPoints(40, ANCHOR, ANCHOR.plusSeconds(60 * 300)));
    }

    @Test
    void exitPoints_cappedAtOneHundred_exactOneHundredAllowed() {
        assertEquals(100, PointsRules.checkExitCap(60, 40), "恰 100 合法");
        assertEquals(100, PointsRules.checkExitCap(0, 100), "首次潜 100 分钟恰上限合法");
        PointsRejectedException ex = assertThrows(PointsRejectedException.class,
                () -> PointsRules.checkExitCap(61, 40));
        assertTrue(ex.getMessage().contains("101"));
    }

    @Test
    void allowedMinutes_ndlMinusCurrentPoints_exactUseUpAllowed_zeroOrLessRejected() {
        assertEquals(30, PointsRules.allowedBottomMinutes(50, 20));
        assertTrue(PointsRules.withinAllowed(30, 30), "恰用尽合法");
        assertFalse(PointsRules.withinAllowed(31, 30), "超过允许时长非法");
        // NDL=20, 积分 20 -> 允许 0，拒绝
        assertThrows(PointsRejectedException.class, () -> PointsRules.allowedBottomMinutes(20, 20));
        // 积分更高 -> 负数，拒绝
        assertThrows(PointsRejectedException.class, () -> PointsRules.allowedBottomMinutes(20, 25));
    }

    @Test
    void surfaceInterval_exactlySixtyPasses_lessThanSixtyRejected_noHistoryPasses() {
        PointsRules.checkSurfaceInterval(null, ANCHOR); // 首次无历史
        PointsRules.checkSurfaceInterval(ANCHOR, ANCHOR.plusSeconds(60L * 60)); // 恰 60 通过
        PointsRejectedException ex = assertThrows(PointsRejectedException.class,
                () -> PointsRules.checkSurfaceInterval(ANCHOR, ANCHOR.plusSeconds(60L * 59)));
        assertTrue(ex.getMessage().contains("60"));
    }

    @Test
    void exitPointsPredictionNeverNegative() {
        assertEquals(0, PointsRules.exitPoints(0, 0));
        assertEquals(70, PointsRules.exitPoints(20, 50));
    }
}
