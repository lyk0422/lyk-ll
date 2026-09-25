package com.example.starter.web.dto;

import com.example.starter.domain.ComponentType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * 入库请求：每袋成分血以唯一条码入库，初始状态为待检。
 *
 * @param barcode       唯一条码
 * @param componentType 成分类型
 * @param expiresAt     效期边界时刻(UTC)，该时刻(含)之前可放行
 */
public record IntakeRequest(
        @NotBlank String barcode,
        @NotNull ComponentType componentType,
        @NotNull Instant expiresAt) {
}
