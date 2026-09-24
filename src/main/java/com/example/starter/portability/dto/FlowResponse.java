package com.example.starter.portability.dto;

import java.time.Instant;

import com.example.starter.portability.model.FlowRecord;
import com.example.starter.portability.model.FlowStatus;

/**
 * 流程状态响应。时间字段为 UTC、毫秒精度；未签码时授权码相关字段为 null。
 */
public record FlowResponse(
        long flowId,
        String msisdn,
        FlowStatus status,
        String authCode,
        Instant authCodeIssuedAt,
        Instant authCodeExpiresAt,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    /**
     * 由流程记录构造响应。
     */
    public static FlowResponse from(FlowRecord flow) {
        return new FlowResponse(
                flow.id(),
                flow.msisdn(),
                flow.status(),
                flow.authCode(),
                flow.authCodeIssuedAt(),
                flow.authCodeExpiresAt(),
                flow.version(),
                flow.createdAt(),
                flow.updatedAt());
    }
}
