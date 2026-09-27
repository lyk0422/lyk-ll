package com.example.starter.api.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 采集会话视图：状态、版本、连续水位线与缓冲情况。
 *
 * @param version          当前版本（从 1 开始，每次成功变更加 1）
 * @param watermark        连续入账水位线：1..watermark 已全部入账
 * @param expectedCount    计划包总数
 * @param bufferedSeqs     当前仍处于缓冲（未消费）的序号，升序
 * @param admittedCount    已入前缀（有力矩记录）的包数
 * @param bufferedCount    缓冲中的包数
 * @param lateCount        归档后迟到包数
 * @param calibrationDate  绑定的工具日校验日期(UTC)快照
 * @param closedAt         关闭时间(UTC)，未关闭为 null
 */
public record CollectionSessionView(
        String sessionNo,
        String cardNo,
        String toolCode,
        LocalDate calibrationDate,
        String status,
        int version,
        int watermark,
        int expectedCount,
        int admittedCount,
        int bufferedCount,
        int lateCount,
        List<Integer> bufferedSeqs,
        Instant createdAt,
        Instant closedAt) {
}
