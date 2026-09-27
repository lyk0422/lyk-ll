package com.example.starter.dto;

/**
 * 单机分摊明细视图。
 * availablePowerKw 为分摊参与出力：未绑定计划时等于实时可用出力；
 * 绑定计划时等于 min(realtimeAvailableKw, plannedPowerKw)。
 * plannedPowerKw/realtimeAvailableKw 仅计划绑定指令非空（UTC 日期与时段见指令基数）。
 */
public record AllocationItem(
        String turbineCode,
        int availablePowerKw,
        int technicalMinKw,
        int targetPowerKw,
        int curtailKw,
        Integer plannedPowerKw,
        Integer realtimeAvailableKw) {
}
