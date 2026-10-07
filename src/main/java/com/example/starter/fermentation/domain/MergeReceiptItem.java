package com.example.starter.fermentation.domain;

import java.math.BigDecimal;

/**
 * 合批接收来源明细。
 *
 * @param id           明细 ID
 * @param receiptId    所属受理单 ID
 * @param batchId      来源批次 ID
 * @param batchNo      来源批次号（受理时快照）
 * @param curveVersion 来源批次受理时的曲线版本快照
 * @param liters       完整接收的源登记清酒量（升）
 */
public record MergeReceiptItem(
        long id,
        long receiptId,
        long batchId,
        String batchNo,
        int curveVersion,
        BigDecimal liters) {
}
