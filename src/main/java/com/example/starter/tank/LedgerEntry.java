package com.example.starter.tank;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 储罐交接台账流水（只追加，不修改）：余额、预占、补液、消耗逐笔守恒的依据。
 *
 * @param id               主键
 * @param stationId        门站ID
 * @param tankId           储罐ID
 * @param requestId        关联请求号
 * @param entryType        REGISTER/RESERVE/CONSUME/RELEASE/REPLENISH
 * @param amountKg         本笔量 kg 三位小数
 * @param balanceAfterKg   本笔后余额 kg 快照
 * @param reservedAfterKg  本笔后预占 kg 快照
 * @param bizTime          业务时刻
 * @param createdAt        落库时刻
 */
public record LedgerEntry(
        Long id,
        Long stationId,
        Long tankId,
        String requestId,
        String entryType,
        BigDecimal amountKg,
        BigDecimal balanceAfterKg,
        BigDecimal reservedAfterKg,
        LocalDateTime bizTime,
        LocalDateTime createdAt) {
}
