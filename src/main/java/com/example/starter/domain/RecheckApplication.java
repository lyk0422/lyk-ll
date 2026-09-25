package com.example.starter.domain;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 复检申请行（每批次至多一条）。
 *
 * @param id         主键
 * @param lotId      批次主键
 * @param requestId  复检受理幂等键
 * @param status     申请状态
 * @param appliedAt  申请提交时间（UTC）
 * @param deadlineAt 受理截止日（UTC 日界，含当日）
 * @param createdAt  入库时间（UTC）
 */
public record RecheckApplication(
        Long id,
        long lotId,
        String requestId,
        String status,
        Instant appliedAt,
        LocalDate deadlineAt,
        Instant createdAt) {
}
