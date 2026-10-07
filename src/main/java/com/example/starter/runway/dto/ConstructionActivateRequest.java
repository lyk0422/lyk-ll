package com.example.starter.runway.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * 施工单生效请求：冻结区间并闭锁整跑道。expectedVersion 乐观锁。
 *
 * @param requestId       幂等请求标识
 * @param expectedVersion 期望的施工单聚合版本，不符返回409
 * @param activatedAt     生效时刻（UTC，精确时刻由请求给出）
 */
public record ConstructionActivateRequest(
        @NotBlank String requestId,
        int expectedVersion,
        @NotNull Instant activatedAt) {
}
