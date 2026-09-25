package com.example.starter.api;

import jakarta.validation.constraints.NotBlank;

/**
 * 预测编制加钞计划请求。amount 为空时按 ATM 历史日均取现预测；
 * 非空时使用指定金额（仍不得超过钞箱容量，恰等容量合法）。
 */
public record CreatePlanRequest(
        @NotBlank String requestId,
        @NotBlank String atmCode,
        Long amount
) {
}
