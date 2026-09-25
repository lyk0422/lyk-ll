package com.example.starter.lc.web.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 信用证信息响应。金额单位与 currency 一致；所有时间为 UTC。
 */
public record LcResponse(
        String lcNo,
        String applicant,
        String beneficiary,
        String currency,
        BigDecimal amountTotal,
        BigDecimal amountAvailable,
        LocalDateTime expiryUtc) {
}
