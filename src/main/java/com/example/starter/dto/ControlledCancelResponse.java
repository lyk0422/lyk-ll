package com.example.starter.dto;

import java.time.Instant;

/**
 * 受控分段计划取消结果。
 */
public record ControlledCancelResponse(
        String orderNo,
        String requestId,
        int planVersion,
        String planStatus,
        String orderStatus,
        Instant cancelledAt,
        boolean replayed) implements ReplayableResponse<ControlledCancelResponse> {

    @Override
    public ControlledCancelResponse asReplay() {
        return new ControlledCancelResponse(orderNo, requestId, planVersion, planStatus,
                orderStatus, cancelledAt, true);
    }
}
