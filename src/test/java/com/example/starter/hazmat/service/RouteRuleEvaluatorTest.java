package com.example.starter.hazmat.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.example.starter.hazmat.po.ControlPeriod;
import com.example.starter.hazmat.po.RouteInfo;
import com.example.starter.hazmat.po.RouteWaypointDef;

/**
 * 路线判定纯逻辑单元测试：类别等级匹配、禁行、管制时段半开区间 [start,end) 边界。
 */
class RouteRuleEvaluatorTest {

    private final RouteInfo level3 = new RouteInfo("R-G3", "高三级线", 3, false);
    private final RouteInfo level2 = new RouteInfo("R-G2", "中二级线", 2, false);
    private final RouteInfo banned = new RouteInfo("R-BAN", "禁行线", 1, true);
    private final List<RouteWaypointDef> defs = List.of(
            new RouteWaypointDef("R-G2", 0, "p0", 30),
            new RouteWaypointDef("R-G2", 1, "p1", 60));
    private final ControlPeriod period = new ControlPeriod(1L, "R-G2",
            LocalDateTime.parse("2026-10-01T08:00:00"),
            LocalDateTime.parse("2026-10-01T10:00:00"));

    @Test
    void levelMatchesWhenRouteHigherOrEqual() {
        LocalDateTime departure = LocalDateTime.parse("2026-10-02T06:00:00");
        // 要求等级 2，路线等级 2/3 均通过；要求 3，路线 2 不通过。
        assertThat(RouteRuleEvaluator.evaluate(level2, 2, departure, defs, List.of()).allowed()).isTrue();
        assertThat(RouteRuleEvaluator.evaluate(level3, 2, departure, defs, List.of()).allowed()).isTrue();

        var mismatch = RouteRuleEvaluator.evaluate(level2, 3, departure, defs, List.of());
        assertThat(mismatch.allowed()).isFalse();
        assertThat(mismatch.verdict()).isEqualTo(RouteRuleEvaluator.Verdict.LEVEL_MISMATCH);
    }

    @Test
    void forbiddenRouteAlwaysRejected() {
        var result = RouteRuleEvaluator.evaluate(banned, 1,
                LocalDateTime.parse("2026-10-02T06:00:00"), defs, List.of());
        assertThat(result.verdict()).isEqualTo(RouteRuleEvaluator.Verdict.FORBIDDEN_ROUTE);
    }

    @Test
    void halfOpenControlPeriodBoundary() {
        // 恰在起始时刻 == start：受管制。
        assertThat(RouteRuleEvaluator.inControlPeriod(period.startTime(), period)).isTrue();
        // 恰在结束时刻 == end：不受管制。
        assertThat(RouteRuleEvaluator.inControlPeriod(period.endTime(), period)).isFalse();
        // 区间内受管制，区间外不受。
        assertThat(RouteRuleEvaluator.inControlPeriod(LocalDateTime.parse("2026-10-01T09:59:59"), period)).isTrue();
        assertThat(RouteRuleEvaluator.inControlPeriod(LocalDateTime.parse("2026-10-01T07:59:59"), period)).isFalse();
        assertThat(RouteRuleEvaluator.inControlPeriod(LocalDateTime.parse("2026-10-01T10:00:01"), period)).isFalse();
    }

    @Test
    void waypointPlannedAtPeriodStartConflicts() {
        // 发运 07:30，p0 计划 08:00 == 管制起始 → 冲突，冲突序号 0。
        var conflict = RouteRuleEvaluator.evaluate(level2, 1,
                LocalDateTime.parse("2026-10-01T07:30:00"), defs, List.of(period));
        assertThat(conflict.verdict()).isEqualTo(RouteRuleEvaluator.Verdict.CONTROL_PERIOD_CONFLICT);
        assertThat(conflict.conflictSeq()).isZero();
    }

    @Test
    void waypointPlannedAtPeriodEndAllowed() {
        // 发运 09:30，p0 计划 10:00 == 管制结束（不受管制），p1 10:30 区间外 → 通过。
        var allowed = RouteRuleEvaluator.evaluate(level2, 1,
                LocalDateTime.parse("2026-10-01T09:30:00"), defs, List.of(period));
        assertThat(allowed.verdict()).isEqualTo(RouteRuleEvaluator.Verdict.ALLOWED);
    }
}
