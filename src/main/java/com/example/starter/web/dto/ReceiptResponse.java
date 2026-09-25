package com.example.starter.web.dto;

import java.util.List;

/**
 * 无害化处理回执登记响应。
 *
 * @param batchNo        屠宰批次号
 * @param receiptNo      回执编号
 * @param category       回执类别
 * @param headCount      回执头只数
 * @param late           是否核销后迟到登记（仅记录并告警，不改变批次状态）
 * @param reconciliation 当前回执勾稽情况
 */
public record ReceiptResponse(
        String batchNo,
        String receiptNo,
        String category,
        int headCount,
        boolean late,
        List<ReconciliationEntry> reconciliation) {
}
