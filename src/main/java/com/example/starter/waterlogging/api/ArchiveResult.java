package com.example.starter.waterlogging.api;

import java.time.LocalDateTime;

/**
 * 复盘归档结果；终态重复归档返回同一结果（replay=true）。
 */
public record ArchiveResult(
        Long planId,
        String requestId,
        String status,
        LocalDateTime archivedAt,
        String remark) {
}
