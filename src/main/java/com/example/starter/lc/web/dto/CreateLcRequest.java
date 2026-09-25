package com.example.starter.lc.web.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 开立合成信用证请求。
 *
 * @param expiryUtc 交单有效期截止时刻(UTC)；为空表示长期有效
 */
public record CreateLcRequest(
        @NotBlank String lcNo,
        @NotBlank String applicant,
        @NotBlank String beneficiary,
        String currency,
        @NotNull @Positive BigDecimal amountTotal,
        LocalDateTime expiryUtc) {
}
