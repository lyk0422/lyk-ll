package com.example.starter.greenhouse.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * 作物及其生育期允许窗口的创建请求（合成数据维护入口）。
 * 温度单位 ℃，湿度单位 %RH。
 */
public record CropCreateRequest(
        @NotBlank(message = "不能为空") String cropCode,
        @NotBlank(message = "不能为空") String name,
        @NotBlank(message = "不能为空") String stageCode,
        @NotNull @DecimalMin("-50.00") @DecimalMax("60.00") BigDecimal dayTempMin,
        @NotNull @DecimalMin("-50.00") @DecimalMax("60.00") BigDecimal dayTempMax,
        @NotNull @DecimalMin("0.00") @DecimalMax("100.00") BigDecimal dayHumidityMin,
        @NotNull @DecimalMin("0.00") @DecimalMax("100.00") BigDecimal dayHumidityMax,
        @NotNull @DecimalMin("-50.00") @DecimalMax("60.00") BigDecimal nightTempMin,
        @NotNull @DecimalMin("-50.00") @DecimalMax("60.00") BigDecimal nightTempMax,
        @NotNull @DecimalMin("0.00") @DecimalMax("100.00") BigDecimal nightHumidityMin,
        @NotNull @DecimalMin("0.00") @DecimalMax("100.00") BigDecimal nightHumidityMax) {
}
