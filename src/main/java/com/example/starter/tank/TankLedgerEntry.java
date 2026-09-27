package com.example.starter.tank;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 储罐台账明细：余额、预占、补液与消耗的守恒勾稽依据。
 *
 * @param id              主键
 * @param tankId          储罐ID
 * @param entryType       REGISTER/RESERVE/RELEASE/CONSUME/REFILL
 * @param amountKg        变动量 kg，恒正
 * @param balanceAfterKg  变动后余额 kg
 * @param reservedAfterKg 变动后预占 kg
 * @param refType         来源：REGISTER/PLAN/PRESSURE/RECEIPT/CANCEL/REFILL
 * @param refId           来源单据ID
 * @param requestId       触发请求的幂等键
 * @param createdAt       入账时刻
 */
public record TankLedgerEntry(
        Long id,
        Long tankId,
        String entryType,
        BigDecimal amountKg,
        BigDecimal balanceAfterKg,
        BigDecimal reservedAfterKg,
        String refType,
        Long refId,
        String requestId,
        LocalDateTime createdAt) {
}
