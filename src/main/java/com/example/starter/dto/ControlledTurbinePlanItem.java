package com.example.starter.dto;

import java.util.List;

/**
 * 受控分段计划单机组视图：冻结的登记参数、最终目标与完整阶梯。
 */
public record ControlledTurbinePlanItem(
        String turbineCode,
        int startPowerKw,
        int rampRateKwPerMin,
        int durationMinutes,
        int targetPowerKw,
        List<ControlledStepItem> steps) {
}
