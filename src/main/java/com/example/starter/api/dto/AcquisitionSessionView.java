package com.example.starter.api.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 采集会话视图：绑定关系、连续水位线、状态与乐观版本。
 *
 * @param calibrationDate 绑定工具日校验版本（创建时快照，UTC 日期）
 * @param watermark       连续水位线：已入账的最大连续序号，0=尚未入账
 * @param version         乐观版本，从 1 开始，成功变更加 1
 */
public record AcquisitionSessionView(
        String sessionNo,
        String cardNo,
        String toolCode,
        LocalDate calibrationDate,
        int watermark,
        String status,
        long version,
        List<String> pointCodes,
        Instant createdAt,
        Instant closedAt) {
}
