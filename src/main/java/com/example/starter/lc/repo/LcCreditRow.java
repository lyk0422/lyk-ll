package com.example.starter.lc.repo;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 信用证表行对象。
 *
 * @param status ACTIVE-有效 CLOSED-结清
 */
public record LcCreditRow(
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
