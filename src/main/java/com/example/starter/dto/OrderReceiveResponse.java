package com.example.starter.dto;

import java.util.List;

/**
 * 限电指令接收/重放响应。replayed=true 表示同键同参重放，内容与首次响应一致。
 */
public record OrderReceiveResponse(
        Long orderId,
        String orderNo,
        String requestId,
        String farmCode,
        int targetPowerKw,
        int farmAvailableKw,
        int curtailedKw,
        String status,
        int cycleNo,
        boolean noCurtailment,
        List<AllocationItem> allocations,
        boolean replayed) {

    /** 同键同参重放时标记 replayed=true，其余内容与首次响应一致。 */
    public OrderReceiveResponse asReplay() {
        return new OrderReceiveResponse(orderId, orderNo, requestId, farmCode, targetPowerKw,
                farmAvailableKw, curtailedKw, status, cycleNo, noCurtailment, allocations, true);
    }
}
