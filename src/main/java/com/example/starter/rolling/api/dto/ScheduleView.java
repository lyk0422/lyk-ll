package com.example.starter.rolling.api.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 排产单视图。
 */
public record ScheduleView(
        Long id,
        String lineCode,
        String unitCode,
        int versionNo,
        Long baseVersionId,
        String status,
        boolean effective,
        boolean locked,
        boolean rollChangeRequired,
        LocalDateTime frozenAt,
        List<ItemView> items,
        List<WindowView> windows) {
}
