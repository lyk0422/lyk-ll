package com.example.starter.greenhouse.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * 策略“编制”请求：提交昼/夜温湿度设定点（目标值，℃ / %RH）与容差半带，初始为 DRAFT。
 * 设定点是否落在作物生育期窗口内由服务端校验（恰在窗口边界合法）。
 */
public record StrategyDraftRequest(
        @NotBlank(message = "不能为空") @Size(max = 64) String strategyCode,
        @NotBlank(message = "不能为空") String cropCode,
        @NotBlank(message = "不能为空") String zoneCode,
        @NotNull @DecimalMin("-50.00") @DecimalMax("60.00") BigDecimal dayTempTarget,
        @NotNull @DecimalMin("0.00") BigDecimal dayTempTolerance,
        @NotNull @DecimalMin("0.00") @DecimalMax("100.00") BigDecimal dayHumidityTarget,
        @NotNull @DecimalMin("0.00") BigDecimal dayHumidityTolerance,
        @NotNull @DecimalMin("-50.00") @DecimalMax("60.00") BigDecimal nightTempTarget,
        @NotNull @DecimalMin("0.00") BigDecimal nightTempTolerance,
        @NotNull @DecimalMin("0.00") @DecimalMax("100.00") BigDecimal nightHumidityTarget,
        @NotNull @DecimalMin("0.00") BigDecimal nightHumidityTolerance) {
}
