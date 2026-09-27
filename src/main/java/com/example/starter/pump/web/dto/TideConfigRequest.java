package com.example.starter.pump.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * 分区潮汐顶托控制配置新增/修改。
 *
 * @param requestId      幂等键
 * @param zoneCode       流域分区代码
 * @param thresholdLevel 顶托门槛潮位（米，非负）；潮位大于等于此值进入强排，恰等进入
 * @param expectedVersion 期望的工况配置版本：首次新增传0（成功后为1），修改须传当前版本，成功加1
 */
public record TideConfigRequest(
        @NotBlank String requestId,
        @NotBlank String zoneCode,
        @NotNull @DecimalMin(value = "0.0", inclusive = true) BigDecimal thresholdLevel,
        @NotNull @Min(0) Integer expectedVersion
) {
}
