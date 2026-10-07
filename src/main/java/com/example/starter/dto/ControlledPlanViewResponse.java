package com.example.starter.dto;

import java.time.Instant;
import java.util.List;

/**
 * 受控分段计划查询视图：当前版本、状态、进度与完整冻结阶梯。
 */
public record ControlledPlanViewResponse(
        String orderNo,
        long orderId,
        int planVersion,
        String planStatus,
        String orderStatus,
        Instant startAt,
        int durationMinutes,
        int confirmedMinute,
        List<ControlledTurbinePlanItem> turbines) {
}
