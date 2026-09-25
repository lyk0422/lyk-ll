package com.example.starter.schedule.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 撤销已编排窗口入参（按申请幂等键定位）。
 */
public record CancelRequestDto(@NotBlank String requestId) {
}
