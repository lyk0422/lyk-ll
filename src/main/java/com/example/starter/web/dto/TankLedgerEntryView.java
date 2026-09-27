package com.example.starter.web.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 储罐台账明细（只读视图）：余额与预占变动的守恒证据。
 *
 * @param id              台账行ID
 * @param entryType       REGISTER/ADJUST/RESERVE/EXECUTE/RELEASE/REFILL
 * @param refCode         来源单号（写操作 requestId）
 * @param deltaBalanceKg  余额变动 kg（±）
 * @param deltaReservedKg 预占变动 kg（±）
 * @param balanceAfterKg  入账后余额 kg
 * @param reservedAfterKg 入账后预占 kg
 * @param bizDate         台账业务日期（Asia/Shanghai）
 * @param createdAt       入账时刻
 */
public record TankLedgerEntryView(
        Long id,
        String entryType,
        String refCode,
        BigDecimal deltaBalanceKg,
        BigDecimal deltaReservedKg,
        BigDecimal balanceAfterKg,
        BigDecimal reservedAfterKg,
        LocalDate bizDate,
        LocalDateTime createdAt) {
}
