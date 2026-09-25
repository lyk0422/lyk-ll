package com.example.starter.portability.web;

import java.time.Instant;

import com.example.starter.portability.FlowState;

/**
 * 生效广播响应：包含生效时刻与触发本次生效的幂等请求号。
 */
public record BroadcastView(
        Long flowId,
        String msisdn,
        FlowState state,
        String broadcastRequestId,
        Instant effectiveAt) {
}
