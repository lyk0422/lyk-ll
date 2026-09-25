package com.example.starter.hazmat.service;

import java.time.LocalDateTime;
import java.util.List;

import com.example.starter.hazmat.po.ControlPeriod;
import com.example.starter.hazmat.po.RouteInfo;
import com.example.starter.hazmat.po.RouteWaypointDef;

/**
 * 路线通行规则纯判定：禁行、危化品类别与通行等级匹配、管制时段半开区间 [start,end)。
 *
 * <p>本类无状态、不访问数据库，时间判定可完全复算。
 */
public final class RouteRuleEvaluator {

    /** 路线核准结论。 */
    public enum Verdict {
        /** 核准通过。 */
        ALLOWED,
        /** 禁行路线。 */
        FORBIDDEN_ROUTE,
        /** 类别要求等级高于路线通行等级。 */
        LEVEL_MISMATCH,
        /** 某途经点计划通过时刻落入管制时段。 */
        CONTROL_PERIOD_CONFLICT
    }

    /** 判定结果（含首个冲突途经点序号，管制冲突时使用；否则为 -1）。 */
    public record Result(Verdict verdict, int conflictSeq, String message) {

        public boolean allowed() {
            return verdict == Verdict.ALLOWED;
        }
    }

    private RouteRuleEvaluator() {
    }

    /**
     * 判定一条路线是否可核准。
     *
     * @param route            路线信息
     * @param requiredLevel    危化品类别要求的最低通行等级
     * @param plannedDeparture 计划发运时刻
     * @param waypointDefs     路线途经点定义（按序号）
     * @param controlPeriods   该路线管制时段
     */
    public static Result evaluate(RouteInfo route, int requiredLevel, LocalDateTime plannedDeparture,
                                  List<RouteWaypointDef> waypointDefs, List<ControlPeriod> controlPeriods) {
        if (route.forbidden()) {
            return new Result(Verdict.FORBIDDEN_ROUTE, -1,
                    "路线 " + route.routeCode() + " 为禁行路线，禁止危化品运输");
        }
        if (route.passLevel() < requiredLevel) {
            return new Result(Verdict.LEVEL_MISMATCH, -1,
                    "路线 " + route.routeCode() + " 通行等级 " + route.passLevel()
                            + " 低于危化品要求等级 " + requiredLevel);
        }
        for (RouteWaypointDef def : waypointDefs) {
            LocalDateTime planned = plannedDeparture.plusMinutes(def.offsetMinutes());
            for (ControlPeriod period : controlPeriods) {
                if (inControlPeriod(planned, period)) {
                    return new Result(Verdict.CONTROL_PERIOD_CONFLICT, def.seq(),
                            "途经点 " + def.seq() + "(" + def.name() + ") 计划通过时刻 " + planned
                                    + " 落入管制时段 [" + period.startTime() + ", " + period.endTime() + ")");
                }
            }
        }
        return new Result(Verdict.ALLOWED, -1, "OK");
    }

    /**
     * 半开区间判定：{@code start <= planned < end}。
     * 计划通过时刻恰等于起始时刻 → 受管制；恰等于结束时刻 → 不受管制。
     */
    public static boolean inControlPeriod(LocalDateTime planned, ControlPeriod period) {
        return !planned.isBefore(period.startTime()) && planned.isBefore(period.endTime());
    }
}
