package com.example.starter.slaughter.persistence;

import com.example.starter.slaughter.domain.JudgmentCategory;

import java.time.Instant;

/**
 * 无害化处理回执行对象。
 *
 * @param accepted 勾稽恰等受理为 true；不等为 false 并拒绝出厂
 * @param late     核销终态后到达的迟到回执
 * @param alerted  迟到回执是否已告警
 */
public record ReceiptRow(
        Long id,
        String receiptNo,
        String batchNo,
        JudgmentCategory category,
        int headCount,
        boolean accepted,
        boolean late,
        boolean alerted,
        Instant receivedAtUtc) {
}
