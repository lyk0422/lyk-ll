package com.example.starter.dto;

/**
 * 单机分摊明细视图。
 */
public record AllocationItem(
        String turbineCode,
        int availablePowerKw,
        int technicalMinKw,
        int targetPowerKw,
        int curtailKw) {
}
