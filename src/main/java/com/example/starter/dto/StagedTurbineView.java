package com.example.starter.dto;

/**
 * 分段计划参与机组视图：冻结的起始功率、最终目标、斜率与期限，以及按斜率到达目标所需分钟数。
 */
public record StagedTurbineView(
        String turbineCode,
        int startPowerKw,
        int targetPowerKw,
        int rampKwPerMin,
        int deadlineMinutes,
        int neededMinutes) {
}
