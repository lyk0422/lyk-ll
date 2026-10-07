package com.example.starter.fermentation.domain;

import java.math.BigDecimal;

/**
 * 合批接收来源明细：完整接收该源批登记量，不做部分转出。
 *
 * @param id           明细 ID
 * @param receiptId    所属合批接收单 ID
 * @param batchId      来源批次 ID
 * @param volumeLiters 该来源完整接收的登记升数
 */
public record MergeReceiptSource(
        long id,
        long receiptId,
        long batchId,
        BigDecimal volumeLiters) {
}
