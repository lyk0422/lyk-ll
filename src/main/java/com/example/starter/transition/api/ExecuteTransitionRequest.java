package com.example.starter.transition.api;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * 改品种执行请求：实际关键参数须落入新品种工艺窗口（恰边界合格），并登记切换用时。
 *
 * @param requestId     幂等键
 * @param orderNo       改品种单号
 * @param switchSeconds 实际切换用时，单位秒
 * @param machineSpeed  实际车速 m/min
 * @param consistency   实际上料浓度 %
 */
public record ExecuteTransitionRequest(
        @NotBlank String requestId,
        @NotBlank String orderNo,
        @NotNull Integer switchSeconds,
        @NotNull @DecimalMin("0.0") BigDecimal machineSpeed,
        @NotNull @DecimalMin("0.0") BigDecimal consistency
) {
}
