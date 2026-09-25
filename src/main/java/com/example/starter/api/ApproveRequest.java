package com.example.starter.api;

import jakarta.validation.constraints.NotBlank;

/**
 * 审批请求：审批通过后开始计算 4 小时押运时限。
 */
public record ApproveRequest(
        @NotBlank String requestId
) {
}
