package com.example.starter.domain;

import java.time.LocalDateTime;

/**
 * 联动处置指令行记录。
 */
public record DisposalCommand(
        long id,
        long zoneId,
        long alarmId,
        CommandType commandType,
        CommandStatus status,
        LocalDateTime createdAt,
        LocalDateTime confirmDeadline,
        LocalDateTime confirmedAt,
        LocalDateTime escalatedAt
) {
}
