package com.example.starter.deicing.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * 补料入库登记请求体。
 *
 * @param batchNo        批次号，唯一
 * @param fluidCode      除冰液编码
 * @param quantityLiters 入库量（升）
 * @param expiryTime     效期截止（UTC），必须晚于当前时刻
 */
public record InboundRequest(
        @NotBlank String batchNo,
        @NotBlank String fluidCode,
        @NotNull @DecimalMin("0.01") BigDecimal quantityLiters,
        @NotNull Instant expiryTime) {
}
