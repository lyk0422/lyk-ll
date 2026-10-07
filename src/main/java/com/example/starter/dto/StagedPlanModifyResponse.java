package com.example.starter.dto;

import java.time.Instant;
import java.util.List;

/**
 * 分段计划修改/重放响应：重排后的版本号、启动时刻与机组斜率。
 */
public record StagedPlanModifyResponse(
        String requestId,
        String orderNo,
        Long planId,
        int planVersion,
        String planStatus,
        Instant startAt,
        int durationMinutes,
        List<StagedTurbineView> turbines,
        boolean replayed) {

    public StagedPlanModifyResponse asReplay() {
        return new StagedPlanModifyResponse(requestId, orderNo, planId, planVersion, planStatus,
                startAt, durationMinutes, turbines, true);
    }
}
