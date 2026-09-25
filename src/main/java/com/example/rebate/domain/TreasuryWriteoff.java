package com.example.rebate.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 退库核销登记，按 批次+国库流水号 唯一。
 *
 * @param diffRatio 差额比例=|到账-申报税额|÷申报税额；恰 0.005 视为一致
 * @param consistent 金额是否一致
 * @param manualFlag 是否已登记差异并转人工
 */
public record TreasuryWriteoff(
        Long id,
        Long batchId,
        String treasurySerial,
        BigDecimal declaredTax,
        BigDecimal receivedAmount,
        BigDecimal diffAmount,
        BigDecimal diffRatio,
        boolean consistent,
        boolean manualFlag,
        Instant registeredAt) {
}
