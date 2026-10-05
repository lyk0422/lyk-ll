package com.example.starter.deicing.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 人工确认请求体：库存补足后确认两步法申请进入排队。
 */
public record ManualConfirmRequest(@NotBlank String requestId) {
}
