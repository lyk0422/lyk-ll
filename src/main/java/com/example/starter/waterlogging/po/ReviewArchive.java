package com.example.starter.waterlogging.po;

import java.time.LocalDateTime;

/**
 * 复盘归档 review_archive 映射。
 */
public record ReviewArchive(
        Long id,
        Long planId,
        String requestId,
        String fingerprint,
        LocalDateTime archivedAt,
        String remark,
        LocalDateTime createdAt) {
}
