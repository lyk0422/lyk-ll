package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 创建工具采集会话请求：绑定工序卡与力矩工具（创建时快照工具日校验版本与紧固点清单）。
 * 会话版本从 1 开始，创建无需 expectedVersion。
 */
public record CollectionSessionCreateRequest(
        @NotBlank String requestId,
        @NotBlank String sessionNo,
        @NotBlank String cardNo,
        @NotBlank String toolCode) {
}
