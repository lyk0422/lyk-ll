package com.example.starter.plan.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 物理审核请求。
 *
 * @param requestId 幂等请求ID
 * @param reviewer  物理师（合成）
 */
public record ReviewRequest(@NotBlank String requestId, @NotBlank String reviewer) {
}
