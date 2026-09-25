package com.example.starter.torque.dto;

import java.time.Instant;

/**
 * 力矩工具登记/日校验结果。
 */
public record ToolView(
        String toolNo,
        java.time.LocalDate lastCheckUtcDate,
        boolean validToday,
        Instant updatedAt
) {
}
