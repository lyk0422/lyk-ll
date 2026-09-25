package com.example.starter.web;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 复检申请受理结果。
 *
 * @param lotNo      批次号
 * @param requestId  受理幂等键
 * @param status     申请状态
 * @param appliedAt  受理时间（UTC）
 * @param deadlineAt 受理截止日（UTC 日界，含当日）
 */
public record RecheckView(
        String lotNo,
        String requestId,
        String status,
        Instant appliedAt,
        LocalDate deadlineAt) {
}
