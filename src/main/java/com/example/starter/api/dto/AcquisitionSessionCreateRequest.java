package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 创建工具采集会话请求：绑定工序卡与力矩工具（日校验版本在创建时快照）。
 * 会话版本从 1 开始，创建不携带 expectedVersion。
 */
public record AcquisitionSessionCreateRequest(
        @NotBlank String requestId,
        @NotBlank String sessionNo,
        @NotBlank String cardNo,
        @NotBlank String toolCode) {
}
