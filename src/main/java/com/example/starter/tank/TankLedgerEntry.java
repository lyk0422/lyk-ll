package com.example.starter.tank;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 储罐台账分录：余额与预占的每一次变动追加一条，守恒勾稽依据。
 *
 * @param id              分录ID
 * @param tankId          储罐ID
 * @param entryType       REGISTER/RESERVE/RELEASE/CONSUME/REFILL
 * @param balanceDeltaKg  余额变动 kg（正增负减）
 * @param reservedDeltaKg 预占变动 kg（正增负减）
 * @param balanceAfterKg  入账后余额 kg 快照
 * @param reservedAfterKg 入账后预占 kg 快照
 * @param refType         来源单据：REGISTER/PLAN/RECEIPT/CANCEL/REFILL/PRESSURE
 * @param refId           来源单据ID，登记为 null
 * @param requestId       触发该分录的幂等键
 * @param bizAt           业务时刻（Asia/Shanghai）
 */
public record TankLedgerEntry(Long id, Long tankId, String entryType, BigDecimal balanceDeltaKg,
                              BigDecimal reservedDeltaKg, BigDecimal balanceAfterKg,
                              BigDecimal reservedAfterKg, String refType, Long refId,
                              String requestId, LocalDateTime bizAt) {
}
