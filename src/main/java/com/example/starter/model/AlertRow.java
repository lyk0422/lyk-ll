package com.example.starter.model;

import java.time.LocalDateTime;

/**
 * 逾期升级告警行：保留首次缺陷登记时刻 firstDetectedAt。
 */
public record AlertRow(
        long id,
        long repairOrderId,
        long bladeId,
        long defectId,
        LocalDateTime firstDetectedAt,
        LocalDateTime alertAt,
        String message) {
}
