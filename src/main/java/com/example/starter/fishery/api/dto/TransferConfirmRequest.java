package com.example.starter.fishery.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 跨船配额调剂确认请求。
 *
 * @param requestId 幂等键
 * @param vesselCode 确认方渔船编号（须为调出方或调入方）
 */
public record TransferConfirmRequest(
        @NotBlank String requestId,
        @NotBlank String vesselCode) {
}
