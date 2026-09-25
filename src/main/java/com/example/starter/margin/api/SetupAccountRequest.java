package com.example.starter.margin.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;

/**
 * 合成账户装配请求：一次性创建账户、逐笔负债与首条已确认快照（总资产/总负债）。
 * 仅用于本地演示与测试造数，不连接任何外部行情系统。
 *
 * @param accountNo    账户号
 * @param clientName   客户名称
 * @param currency     记账币种（ISO 4217）
 * @param totalAssets  首条已确认快照总资产，单位元
 * @param liabilities  逐笔负债（编号+金额），金额合计即首条快照总负债
 */
public record SetupAccountRequest(
        @NotBlank String accountNo,
        @NotBlank String clientName,
        @NotBlank String currency,
        @NotNull @DecimalMin(value = "0.00", inclusive = true) BigDecimal totalAssets,
        @NotEmpty @Valid List<LiabilitySeed> liabilities
) {
    /**
     * 单笔合成负债。
     *
     * @param liabilityNo 负债业务编号
     * @param amount      未偿金额，单位元
     */
    public record LiabilitySeed(
            @NotBlank String liabilityNo,
            @NotNull @DecimalMin(value = "0.01", inclusive = true) BigDecimal amount
    ) {
    }
}
