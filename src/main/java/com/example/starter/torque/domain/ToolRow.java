package com.example.starter.torque.domain;

import java.time.LocalDate;

/** torque_tool 表行记录。 */
public record ToolRow(
        long id,
        String toolNo,
        LocalDate lastCheckUtcDate
) {
}
