package com.example.rebate.web.dto;

import java.time.Instant;
import java.util.List;

/**
 * 汇总到账母凭证视图：母凭证本身与各分配行（含分配后目标追缴明细状态，便于勾稽）。
 *
 * @param bankSerial 唯一真实银行流水号（全凭证只出现一次）
 * @param totalCents 到账总额（整数分），恒等于 allocations 金额合计
 * @param receivedAt 到账时刻（UTC）
 * @param version 母凭证版本（创建为 1，不可变）
 * @param allocations 分配行（内部行号标识，非银行流水）
 */
public record ClawbackBatchReceiptView(
        Long id,
        String batchNo,
        String bankSerial,
        long totalCents,
        Instant receivedAt,
        int version,
        Instant createdAt,
        List<AllocationView> allocations) {

    /**
     * 分配行视图：内部分摊标识与分配后目标追缴明细的勾稽状态。
     *
     * @param lineId 分配行主键（独立内部标识）
     * @param lineNo 分配行内部行号（母凭证内从 1 递增）
     * @param correctionNo 目标冲回单更正编号
     * @param clawbackNo 目标追缴明细独立编号
     * @param amountCents 本行分摊金额（整数分）
     * @param collectedCents 分配后该追缴明细累计已收（整数分）
     * @param remainingCents 分配后该追缴明细尚未收回（整数分）
     * @param detailStatus 分配后该追缴明细状态（COLLECTING / COMPLETED）
     * @param detailVersion 分配后该追缴明细版本（本次分配成功加 1）
     */
    public record AllocationView(
            Long lineId,
            int lineNo,
            String correctionNo,
            String clawbackNo,
            Long orderId,
            long amountCents,
            long collectedCents,
            long remainingCents,
            String detailStatus,
            int detailVersion) {
    }
}
