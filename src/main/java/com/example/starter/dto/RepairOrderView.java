package com.example.starter.dto;

import java.time.LocalDateTime;

/**
 * 维修单视图：维修台账条目，closeResult 未结案为 null。
 */
public record RepairOrderView(
        long id,
        long defectId,
        long bladeId,
        String status,
        LocalDateTime deadlineAt,
        LocalDateTime closedAt,
        String closeResult,
        boolean escalated) {
}
