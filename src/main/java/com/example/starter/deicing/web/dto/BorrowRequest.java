package com.example.starter.deicing.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * 跨班组借用申请请求体。
 *
 * @param requestId      幂等键，由调用方保证唯一
 * @param fluidCode      除冰液编码
 * @param tankCode       出借液罐编号，同时刻仅允许一张未归还借用单
 * @param quantityLiters 借出量（升）
 * @param fromTeam       借出班组
 * @param toTeam         借入班组
 */
public record BorrowRequest(
        @NotBlank String requestId,
        @NotBlank String fluidCode,
        @NotBlank String tankCode,
        @NotNull @DecimalMin("0.01") BigDecimal quantityLiters,
        @NotBlank String fromTeam,
        @NotBlank String toTeam) {
}
