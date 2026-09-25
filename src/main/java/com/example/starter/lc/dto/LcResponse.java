package com.example.starter.lc.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Instant;

/**
 * 信用证信息。
 *
 * @param lcNo            信用证编号
 * @param applicant       开证申请人
 * @param beneficiary     受益人
 * @param currency        币种
 * @param totalAmount     总金额（币种元）
 * @param availableAmount 当前可用额度（币种元）
 * @param expiryDate      交单有效期（UTC 日期，含当日）
 * @param status          ACTIVE/CLOSED
 * @param createdAt       创建时间（UTC）
 */
public record LcResponse(
        String lcNo,
        String applicant,
        String beneficiary,
        String currency,
        BigDecimal totalAmount,
        BigDecimal availableAmount,
        LocalDate expiryDate,
        String status,
        Instant createdAt) {
}
