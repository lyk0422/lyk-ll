package com.example.starter.invoice.web;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 发票响应。
 *
 * @param invoiceNo          发票号码
 * @param direction          发票方向：BLUE/RED
 * @param status             发票状态
 * @param sellerTaxNo        销方纳税人识别号
 * @param buyerTaxNo         购方纳税人识别号
 * @param buyerBooked        购方是否已入账
 * @param totalAmount        价税合计，单位：元
 * @param redFlushedAmount   累计已红冲金额，单位：元
 * @param redRemainAmount    可红冲余额，单位：元
 * @param originalInvoiceNo  红票引用的原蓝票号码，蓝票为 null
 * @param redChain           蓝票的红冲链（红票号码列表），红票为空列表
 * @param lines              行项目
 * @param idempotentReplay   是否为幂等重放（同键同参返回首次结果）
 * @param createdAt          开具时间
 */
public record InvoiceResponse(
        String invoiceNo,
        String direction,
        String status,
        String sellerTaxNo,
        String buyerTaxNo,
        boolean buyerBooked,
        BigDecimal totalAmount,
        BigDecimal redFlushedAmount,
        BigDecimal redRemainAmount,
        String originalInvoiceNo,
        List<String> redChain,
        List<Line> lines,
        boolean idempotentReplay,
        LocalDateTime createdAt) {

    /**
     * 发票行项目响应。
     */
    public record Line(
            int lineNo,
            String itemName,
            BigDecimal quantity,
            BigDecimal unitPrice,
            BigDecimal amount,
            BigDecimal redFlushedAmount) {
    }
}
