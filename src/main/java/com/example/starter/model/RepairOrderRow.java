package com.example.starter.model;

import java.time.LocalDateTime;

/**
 * 维修单行：status IN_PROGRESS/CLOSED；closeResult ON_TIME/LATE，未结案为 null。
 */
public record RepairOrderRow(
        long id,
        long defectId,
        long bladeId,
        String status,
        LocalDateTime deadlineAt,
        LocalDateTime closedAt,
        String closeResult,
        boolean escalated) {

    public boolean inProgress() {
        return "IN_PROGRESS".equals(status);
    }
}
