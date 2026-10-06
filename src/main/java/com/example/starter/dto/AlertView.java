package com.example.starter.dto;

import java.time.LocalDateTime;

/**
 * 逾期升级告警视图：保留首次缺陷登记时刻。
 */
public record AlertView(
        long id,
        long repairOrderId,
        long bladeId,
        long defectId,
        LocalDateTime firstDetectedAt,
        LocalDateTime alertAt,
        String message) {
}
