package com.example.starter.invoice.web;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 红字信息表响应。
 *
 * @param infoNo            信息表编号
 * @param status            状态：PENDING/CONFIRMED/SUSPENDED
 * @param originalInvoiceNo 原蓝票号码
 * @param redType           红冲类型
 * @param totalAmount       本次红冲金额，单位：元
 * @param sellerConfirmed   销方是否已确认
 * @param buyerConfirmed    购方是否已确认
 * @param redInvoiceNo      确认后开具的红票号码，未开具为 null
 * @param lines             部分红冲行项目，全额红冲为空列表
 * @param confirmations     确认轨迹
 * @param idempotentReplay  是否为幂等重放
 * @param createdAt         创建时间
 */
public record RedInfoResponse(
        String infoNo,
        String status,
        String originalInvoiceNo,
        String redType,
        BigDecimal totalAmount,
        boolean sellerConfirmed,
        boolean buyerConfirmed,
        String redInvoiceNo,
        List<Line> lines,
        List<Confirmation> confirmations,
        boolean idempotentReplay,
        LocalDateTime createdAt) {

    /**
     * 部分红冲行项目响应。
     */
    public record Line(int originalLineNo, BigDecimal amount) {
    }

    /**
     * 确认轨迹响应。
     */
    public record Confirmation(String party, String opinion, String comment, LocalDateTime confirmedAt) {
    }
}
