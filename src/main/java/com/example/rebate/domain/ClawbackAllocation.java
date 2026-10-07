package com.example.rebate.domain;

import java.time.Instant;

/**
 * 汇总分配行：母凭证下一张已批准冲回单的分摊金额；行号为独立内部标识，不冒充银行流水。
 *
 * @param receiptId 所属汇总母凭证
 * @param detailId 目标追缴明细
 * @param orderId 目标冲回单（冗余便于勾稽）
 * @param lineNo 分配行内部行号（母凭证内从 1 递增）
 * @param amountCents 本行分摊金额（正数整数分），不超过目标单未收回金额
 */
public record ClawbackAllocation(
        Long id,
        Long receiptId,
        Long detailId,
        Long orderId,
        int lineNo,
        long amountCents,
        Instant createdAt) {
}
