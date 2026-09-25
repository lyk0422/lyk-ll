package com.example.starter.slaughter.dto;

import java.time.OffsetDateTime;

/**
 * 无害化处理回执登记响应。
 *
 * @param receiptNo     回执编号
 * @param batchNo       批次编号
 * @param category      处理类别 RENDERED/DESTROYED
 * @param headCount     回执头只数
 * @param accepted      勾稽恰等受理：true 受理；false 拒绝出厂
 * @param late          是否为核销终态后迟到回执（仅记迟到事件并告警）
 * @param alerted       是否已告警
 * @param receivedAtUtc 回执到达时刻（UTC）
 */
public record ReceiptResponse(
        String receiptNo,
        String batchNo,
        String category,
        int headCount,
        boolean accepted,
        boolean late,
        boolean alerted,
        OffsetDateTime receivedAtUtc) {
}
