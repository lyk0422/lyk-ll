package com.example.starter.domain;

import java.time.LocalDate;

/**
 * 当日禁烧记录（按窑+日期拦截新执行方案）。
 */
public record BanRecord(
        Long id,
        Long planId,
        String kilnId,
        LocalDate banDate,
        String reason,
        java.time.LocalDateTime createdAt) {
}
