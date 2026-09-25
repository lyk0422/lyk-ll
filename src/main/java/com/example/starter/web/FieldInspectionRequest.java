package com.example.starter.web;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * 田间检验（纯度）提交请求。
 *
 * @param requestId   检验提交幂等键
 * @param lotNo       批次号
 * @param purity      品种纯度（百分比，0~100）
 * @param inspector   田间检验员
 * @param inspectedAt 检验时间（UTC，ISO-8601，可空，默认服务当前时间）
 */
public record FieldInspectionRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 40) String lotNo,
        @NotNull @DecimalMin(value = "0.0") @DecimalMax(value = "100.0") BigDecimal purity,
        @NotBlank @Size(max = 100) String inspector,
        String inspectedAt) {
}
