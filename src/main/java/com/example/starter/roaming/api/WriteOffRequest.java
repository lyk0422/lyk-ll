package com.example.starter.roaming.api;

import jakarta.validation.constraints.NotBlank;

/**
 * 差异挂账人工核销请求（须留痕，同一挂账只允许核销一次）。
 *
 * @param requestId    业务幂等键
 * @param operatorName 核销操作人
 * @param remark       核销备注（可空）
 */
public record WriteOffRequest(
        @NotBlank String requestId,
        @NotBlank String operatorName,
        String remark) {
}
