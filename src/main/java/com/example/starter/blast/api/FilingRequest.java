package com.example.starter.blast.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * 起爆备案请求体。
 *
 * @param requestId      幂等键
 * @param applicationId  已审批通过的作业申请 ID
 * @param detonateTime   实际起爆时刻，UTC
 */
public record FilingRequest(
        @NotBlank String requestId,
        @NotNull Long applicationId,
        @NotNull Instant detonateTime) {
}
