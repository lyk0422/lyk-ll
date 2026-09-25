package com.example.starter.plan.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 医师批准请求。
 *
 * @param requestId 幂等请求ID
 * @param physician 批准医师（合成）
 */
public record ApproveRequest(@NotBlank String requestId, @NotBlank String physician) {
}
