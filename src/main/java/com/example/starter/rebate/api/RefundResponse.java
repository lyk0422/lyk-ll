package com.example.starter.rebate.api;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 退库核销登记结果。
 *
 * @param id                登记 ID
 * @param batchId           批次 ID
 * @param treasurySerialNo  国库流水号
 * @param declaredTax       申报税额（元）
 * @param receivedAmount    到账金额（元）
 * @param diffRatio         差额比例
 * @param status            CONSISTENT / DISCREPANCY
 * @param createdAt         登记时间（UTC）
 * @param replayed          是否幂等重放命中
 */
public record RefundResponse(
        Long id,
        Long batchId,
        String treasurySerialNo,
        BigDecimal declaredTax,
        BigDecimal receivedAmount,
        BigDecimal diffRatio,
        String status,
        Instant createdAt,
        boolean replayed
) implements ReplayableResponse<RefundResponse> {

    @Override
    public RefundResponse withReplayed(boolean replayed) {
        return new RefundResponse(id, batchId, treasurySerialNo, declaredTax, receivedAmount,
                diffRatio, status, createdAt, replayed);
    }
}
