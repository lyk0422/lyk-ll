package com.example.starter.tank;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 储罐台账分录：余额、预占与接收预占的每一次变动追加一条，守恒勾稽依据。
 *
 * @param id                     分录ID
 * @param tankId                 储罐ID
 * @param entryType              REGISTER/RESERVE/RELEASE/CONSUME/REFILL/TRANSFER_RESERVE/
 *                               TRANSFER_IN_RESERVE/TRANSFER_OUT/TRANSFER_IN/
 *                               TRANSFER_RELEASE/TRANSFER_IN_RELEASE
 * @param balanceDeltaKg         余额变动 kg（正增负减）
 * @param reservedDeltaKg        预占变动 kg（正增负减）
 * @param incomingReservedDeltaKg 接收预占变动 kg（正增负减）
 * @param balanceAfterKg         入账后余额 kg 快照
 * @param reservedAfterKg        入账后预占 kg 快照
 * @param incomingReservedAfterKg 入账后接收预占 kg 快照
 * @param refType                来源单据：REGISTER/PLAN/RECEIPT/CANCEL/REFILL/PRESSURE/TRANSFER
 * @param refId                  来源单据ID，登记为 null；调拨双侧分录共用同一调拨单ID
 * @param requestId              触发该分录的幂等键
 * @param bizAt                  业务时刻（Asia/Shanghai；调拨分录为 UTC）
 */
public record TankLedgerEntry(Long id, Long tankId, String entryType, BigDecimal balanceDeltaKg,
                              BigDecimal reservedDeltaKg, BigDecimal incomingReservedDeltaKg,
                              BigDecimal balanceAfterKg, BigDecimal reservedAfterKg,
                              BigDecimal incomingReservedAfterKg, String refType, Long refId,
                              String requestId, LocalDateTime bizAt) {
}
