package com.example.starter.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * 进口通关单登记请求（合成数据）。
 *
 * @param requestId      幂等键
 * @param declarationNo  合成通关单号（全局唯一）
 * @param enterpriseName 进口企业名称
 * @param productName    疫苗品种名称
 * @param validUntil     有效截止精确时刻（ISO-8601，UTC）；签发时 now<截止方有效，恰截止拒绝
 * @param totalQuantity  可核销总支数（正整数）
 */
public record RegisterDeclarationRequest(
        @NotBlank String requestId,
        @NotBlank String declarationNo,
        @NotBlank String enterpriseName,
        @NotBlank String productName,
        @NotNull Instant validUntil,
        @NotNull @Min(1) Integer totalQuantity
) {
}
