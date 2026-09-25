package com.example.starter.lc.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 开立信用证请求。
 *
 * @param requestId     幂等键
 * @param lcNo          信用证编号
 * @param applicant     开证申请人
 * @param beneficiary   受益人
 * @param currency      币种（ISO 4217），为空默认 USD
 * @param totalAmount   信用证总金额（币种元）
 * @param expiryDate    交单有效期（UTC 日期，含当日）
 */
public record CreateLcRequest(
        @NotBlank String requestId,
        @NotBlank String lcNo,
        @NotBlank String applicant,
        @NotBlank String beneficiary,
        String currency,
        @NotNull @DecimalMin("0.01") BigDecimal totalAmount,
        @NotNull LocalDate expiryDate) {
}
