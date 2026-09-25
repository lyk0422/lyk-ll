package com.example.starter.pump.web.dto;

import java.time.LocalDateTime;

/**
 * 复盘归档结果。
 *
 * @param pumpdownId 预降事件ID
 * @param status     ARCHIVED 终态
 * @param archivedAt 归档时刻
 * @param summary    复盘摘要（曲线、启停序列、失联台账复算JSON）
 * @param replayed   是否为重复归档幂等返回
 */
public record ArchiveResult(
        Long pumpdownId,
        String status,
        LocalDateTime archivedAt,
        String summary,
        boolean replayed
) {
}
