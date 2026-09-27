package com.example.starter.api.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 采集会话视图：绑定关系、状态、水位线、乐观版本与缓冲序号。
 *
 * @param calibrationDate 绑定的工具日校验版本（UTC 日期，创建时快照）
 * @param watermark       已入账连续前缀末尾序号，0=尚未入账
 * @param version         当前乐观版本号（从 1 开始）
 * @param points          绑定的紧固点清单快照
 * @param bufferedSeqs    仍在乱序缓冲中的包序号（未入账，不参与放行）
 */
public record AcquisitionSessionView(
        String sessionNo,
        String cardNo,
        String toolCode,
        LocalDate calibrationDate,
        String status,
        int watermark,
        int version,
        List<String> points,
        List<Integer> bufferedSeqs,
        Instant createdAt,
        Instant closedAt) {
}
