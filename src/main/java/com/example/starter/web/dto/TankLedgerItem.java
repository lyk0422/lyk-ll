package com.example.starter.web.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 储罐交接台账一条流水的对外视图。
 *
 * @param entryType       REGISTER/RESERVE/CONSUME/RELEASE/REPLENISH
 * @param requestId       关联请求号
 * @param amountKg        本笔量 kg
 * @param balanceAfterKg  本笔后余额 kg
 * @param reservedAfterKg 本笔后预占 kg
 * @param bizTime         业务时刻
 */
public record TankLedgerItem(
        String entryType,
        String requestId,
        BigDecimal amountKg,
        BigDecimal balanceAfterKg,
        BigDecimal reservedAfterKg,
        LocalDateTime bizTime) {
}
