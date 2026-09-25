package com.example.starter.cems.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 限排令确认执行请求（须下达后 2 小时内确认，恰满 2 小时未确认即升级并联动限产）。
 */
public record ConfirmRequest(@NotBlank String requestId, @NotBlank String outfallCode) {
}
