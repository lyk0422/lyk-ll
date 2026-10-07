package com.example.starter.runway.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * 施工单状态动作请求（生效/撤回）：携带聚合乐观锁版本。
 *
 * @param requestId       幂等请求标识
 * @param expectedVersion 期望的施工单聚合版本；不符返回409
 */
public record OrderActionRequest(
        @NotBlank String requestId,
        @Positive int expectedVersion) {
}
