package com.example.starter.dto;

import java.time.Instant;
import java.util.List;

/**
 * 受控分段计划重规划结果：新版本号、新启动时刻与完整新阶梯。
 */
public record ControlledReplanResponse(
        String orderNo,
        String requestId,
        int planVersion,
        Instant startAt,
        int durationMinutes,
        String planStatus,
        List<ControlledTurbinePlanItem> turbines,
        boolean replayed) implements ReplayableResponse<ControlledReplanResponse> {

    @Override
    public ControlledReplanResponse asReplay() {
        return new ControlledReplanResponse(orderNo, requestId, planVersion, startAt,
                durationMinutes, planStatus, turbines, true);
    }
}
