package com.example.starter.reinsurance.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 创建分出保单请求（合成数据）。
 *
 * @param policyNo 保单号
 * @param contractNo 所属合约号
 * @param premium 分出保费（元）
 * @param effectiveDate 生效日期（UTC 日界）
 */
public record CreatePolicyRequest(
        @NotBlank String policyNo,
        @NotBlank String contractNo,
        @NotNull BigDecimal premium,
        @NotNull LocalDate effectiveDate) {
}
