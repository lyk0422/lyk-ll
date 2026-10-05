package com.example.starter.api;

import jakarta.validation.constraints.NotBlank;

/**
 * 让路计划恢复执行请求。仅 YIELDED 状态可恢复；
 * 须满足同一 ATM 无执行中计划且按排队位置轮到该计划。
 */
public record ResumeRequest(
        @NotBlank String requestId
) {
}
