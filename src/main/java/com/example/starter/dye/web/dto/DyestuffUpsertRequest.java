package com.example.starter.dye.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * 染化料主数据建档/补货请求（合成数据维护入口）。
 *
 * @param stockGrams 初始/目标库存克重，单位克
 */
public record DyestuffUpsertRequest(
        @NotBlank String code,
        @NotBlank String name,
        @NotNull @DecimalMin("0") BigDecimal stockGrams) {
}
