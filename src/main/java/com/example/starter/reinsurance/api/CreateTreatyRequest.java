package com.example.starter.reinsurance.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * 创建比例合约请求。
 *
 * @param contractNo 合约号
 * @param cessionRate 分保比例（0~1）
 * @param perPolicyLimit 每张保单再保人责任限额（元）；空表示无限额
 */
public record CreateTreatyRequest(
        @NotBlank String contractNo,
        @NotNull BigDecimal cessionRate,
        BigDecimal perPolicyLimit) {
}
