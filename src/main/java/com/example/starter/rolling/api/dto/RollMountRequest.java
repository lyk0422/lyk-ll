package com.example.starter.rolling.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 轧辊申请上机请求。寿命到期（剩余里程为 0）的轧辊直接拒绝并给出剩余里程。
 */
public record RollMountRequest(
        @NotBlank String requestId,
        @NotBlank String rollNo) {
}
