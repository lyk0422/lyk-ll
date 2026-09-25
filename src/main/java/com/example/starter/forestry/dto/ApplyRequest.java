package com.example.starter.forestry.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 采伐申请提交请求。
 *
 * @param requestId   幂等键
 * @param operatorId  经营主体 ID
 * @param subplotId   小班 ID
 * @param speciesCode 树种编码（同时决定年度限额档位）
 * @param harvestType 采伐类型：MAIN 主伐 / TENDING 抚育 / REFORMATION 改造
 * @param volume      申请采伐蓄积量，单位立方米
 */
public record ApplyRequest(
        @NotBlank String requestId,
        @NotBlank String operatorId,
        @NotBlank String subplotId,
        @NotBlank String speciesCode,
        @NotBlank String harvestType,
        @NotNull @Positive BigDecimal volume
) {
}
