package com.example.starter.runway.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.Instant;

/**
 * 紧急事件结束请求：结束后可另建新例行计划，不复活被抢占项。requestId 幂等。
 *
 * @param requestId       幂等请求标识
 * @param expectedVersion 客户端持有的紧急事件版本号，不匹配返回409
 * @param endedAt         结束时刻（UTC，精确时刻参与指纹）
 */
public record EmergencyEndRequest(
        @NotBlank String requestId,
        @Positive int expectedVersion,
        @NotNull Instant endedAt) {
}
