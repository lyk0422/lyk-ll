package com.example.starter.reinsurance.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 报案/创建赔案请求（合成数据）。创建即按估损金额预提摊回。
 *
 * @param claimNo 赔案号
 * @param policyNo 所属保单号
 * @param estimatedAmount 初始估损金额（元）
 * @param lossDate 出险日期（UTC 日界）
 */
public record CreateClaimRequest(
        @NotBlank String claimNo,
        @NotBlank String policyNo,
        @NotNull BigDecimal estimatedAmount,
        @NotNull LocalDate lossDate) {
}
