package com.example.starter.invoice.repo;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.example.starter.invoice.domain.InvoiceDirection;
import com.example.starter.invoice.domain.InvoiceStatus;

/**
 * 发票行记录（蓝字与红字同表）。
 *
 * @param id                 主键
 * @param requestId          幂等请求号
 * @param fingerprint        幂等指纹
 * @param invoiceNo          发票号码
 * @param direction          发票方向
 * @param status             发票状态
 * @param sellerTaxNo        销方纳税人识别号
 * @param buyerTaxNo         购方纳税人识别号
 * @param buyerBooked        购方是否已入账
 * @param totalAmount        价税合计，单位：元
 * @param redFlushedAmount   累计已红冲金额，单位：元
 * @param originalInvoiceId  红票引用的原蓝票主键，蓝票为 null
 * @param infoTableId        红票来源信息表主键，蓝票为 null
 * @param createdAt          开具时间
 * @param updatedAt          更新时间
 */
public record InvoiceRow(
        long id,
        String requestId,
        String fingerprint,
        String invoiceNo,
        InvoiceDirection direction,
        InvoiceStatus status,
        String sellerTaxNo,
        String buyerTaxNo,
        boolean buyerBooked,
        BigDecimal totalAmount,
        BigDecimal redFlushedAmount,
        Long originalInvoiceId,
        Long infoTableId,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    /**
     * 可红冲余额＝价税合计−累计已红冲金额。
     */
    public BigDecimal redRemainAmount() {
        return totalAmount.subtract(redFlushedAmount);
    }
}
