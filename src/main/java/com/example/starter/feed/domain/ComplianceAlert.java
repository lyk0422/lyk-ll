package com.example.starter.feed.domain;

import java.time.Instant;

/**
 * 合规告警。
 *
 * @param id        主键
 * @param batchNo   相关批次号
 * @param alertType 告警类型
 * @param detail    告警详情
 * @param createdAt 产生时刻（UTC）
 */
public record ComplianceAlert(
        Long id,
        String batchNo,
        AlertType alertType,
        String detail,
        Instant createdAt) {
}
