package com.example.starter.service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Component;

import com.example.starter.store.PlanRepository;
import com.example.starter.store.PlanRepository.SegmentRow;
import com.example.starter.web.ApiException;

/**
 * 引航员可排校验：按实际责任段判定时段重叠，并校验连续作业 4 小时强制休息 1 小时、
 * 单日累计不超过 10 小时的工时规则。责任段已考虑交班切换与接班预占，
 * 未使用交班的计划责任段与计划时段一致（沿用旧语义）。
 */
@Component
public class AssignmentValidator {

    private final PlanRepository plans;

    public AssignmentValidator(PlanRepository plans) {
        this.plans = plans;
    }

    /**
     * 校验引航员在 [start, end) 可承担新责任段。
     *
     * @param pilotId       引航员
     * @param start         新责任段起点（含）
     * @param end           新责任段终点（不含）
     * @param excludePlanId 校验时排除的计划（改期/交班来源计划自身）
     */
    public void validatePilotAssignable(long pilotId, LocalDateTime start, LocalDateTime end,
                                        long excludePlanId) {
        List<SegmentRow> segments = plans.findResponsibilitySegments(pilotId,
                start.minusDays(2), end.plusDays(2), excludePlanId);
        for (SegmentRow segment : segments) {
            if (segment.start().isBefore(end) && segment.end().isAfter(start)) {
                throw ApiException.conflict("引航员在该时段已有计划: " + segment.planId());
            }
        }
        List<WorkHourValidator.Interval> intervals = segments.stream()
                .map(s -> new WorkHourValidator.Interval(s.start(), s.end()))
                .toList();
        WorkHourValidator.validate(intervals, new WorkHourValidator.Interval(start, end))
                .ifPresent(message -> {
                    throw ApiException.conflict(message);
                });
    }
}
