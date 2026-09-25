package com.example.starter.invoice.repo;

import java.math.BigDecimal;

/**
 * 发票行项目记录。
 *
 * @param id                主键
 * @param invoiceId         所属发票主键
 * @param lineNo            行号，从 1 开始
 * @param itemName          项目名称
 * @param quantity          数量
 * @param unitPrice         单价，单位：元
 * @param amount            行金额，单位：元
 * @param redFlushedAmount  本行累计已红冲金额，单位：元
 */
public record InvoiceLineRow(
        long id,
        long invoiceId,
        int lineNo,
        String itemName,
        BigDecimal quantity,
        BigDecimal unitPrice,
        BigDecimal amount,
        BigDecimal redFlushedAmount) {

    /**
     * 本行可红冲余额。
     */
    public BigDecimal redRemainAmount() {
        return amount.subtract(redFlushedAmount);
    }
}
