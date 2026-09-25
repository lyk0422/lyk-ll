package com.example.starter.web.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 粮仓详情视图（含状态、两轮复测与作业窗，便于联查复算）。
 */
public record SiloView(
        String code,
        String name,
        String grainType,
        double safeTemperature,
        double maxGradient,
        double equilibriumHumidity,
        String status,
        int ventilationRounds,
        LocalDateTime createdAt
) {

    public record SiloDetail(SiloView silo, List<OperationView> operations, List<RetestView> retests) {
    }
}
