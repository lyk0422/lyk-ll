package com.example.starter.web.dto;

import java.time.LocalDateTime;

/**
 * 温度曲线点（可复算的历史读数视图）。
 */
public record TemperaturePoint(
        String siloCode,
        String pointCode,
        Double temperature,
        Double humidity,
        LocalDateTime measuredAt,
        String phase
) {
}
