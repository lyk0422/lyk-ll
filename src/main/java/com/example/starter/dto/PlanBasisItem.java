package com.example.starter.dto;

/**
 * 计划绑定指令受理时逐机组冻结的基数证据视图。
 * plannedPowerKw 冻结的该段核定计划；realtimeAvailableKw 受理瞬间实时可用；
 * participatingKw 可参与出力 = min(实时可用, 核定计划)。
 */
public record PlanBasisItem(
        String turbineCode,
        int plannedPowerKw,
        int realtimeAvailableKw,
        int participatingKw,
        int technicalMinKw,
        int targetPowerKw,
        int curtailKw) {
}
