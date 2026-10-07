package com.example.starter.runway.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 施工单撤回请求：仅未生效单据可撤回；已生效不得删除或缩段。expectedVersion 乐观锁。
 *
 * @param requestId       幂等请求标识
 * @param expectedVersion 期望的施工单聚合版本，不符返回409
 */
public record ConstructionWithdrawRequest(
        @NotBlank String requestId,
        int expectedVersion) {
}
