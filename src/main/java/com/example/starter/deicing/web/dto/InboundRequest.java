package com.example.starter.deicing.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/**
 * 补料入库请求体：按批次登记效期。
 *
 * @param batchNo    批次号，唯一；重复批次号拒绝登记
 * @param fluidCode  除冰液编码
 * @param quantity   入库量（升）
 * @param expiryTime 效期截止（UTC）
 */
public record InboundRequest(
        @NotBlank String batchNo,
        @NotBlank String fluidCode,
        @Min(1) int quantity,
        @NotNull Instant expiryTime) {
}
