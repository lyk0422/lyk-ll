package com.example.starter.inspection;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 快检请求。requestId 为幂等键；抑制率单位 %，取值 0~100。
 */
public record RapidTestRequest(
        @NotBlank(message = "requestId 不能为空") String requestId,
        @NotBlank(message = "检测项目不能为空") String item,
        @NotNull(message = "抑制率不能为空")
        @DecimalMin(value = "0", message = "抑制率不能小于 0")
        @DecimalMax(value = "100", message = "抑制率不能大于 100")
        BigDecimal inhibitionRate) {
}
