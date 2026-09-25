package com.example.starter.fermentation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * 清酒转出请求（终态），requestId 幂等；重复转出返回幂等成功。
 */
public record TransferRequest(
        @NotBlank String requestId,
        @NotBlank String batchNo,
        @NotNull Instant transferredAt) {
}
