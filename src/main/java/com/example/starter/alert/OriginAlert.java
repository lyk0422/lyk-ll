package com.example.starter.alert;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 产地预警台账：同一产地同品种当日（UTC 日界）累计 3 批疑似即触发。时间均为 UTC。
 */
public record OriginAlert(
        long id,
        String origin,
        String variety,
        LocalDate alertDate,
        int triggerCount,
        String status,
        LocalDateTime createdAt) {
}
