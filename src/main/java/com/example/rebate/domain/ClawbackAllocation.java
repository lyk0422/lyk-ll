package com.example.rebate.domain;

import java.time.Instant;

/**
 * 汇总分配行：母凭证分摊到一张追缴明细的金额。分配行仅有独立内部标识，
 * 不是银行流水，不进入真实流水命名空间。
 *
 * @param masterId 所属母凭证
 * @param detailId 目标追缴明细
 * @param orderId 目标冲回单（冗余便于查询与校验）
 * @param allocationNo 分配行独立内部标识（全局唯一，非银行流水号）
 * @param amountCents 本分摊金额（正数整数分），不超过该单未收回金额
 */
public record ClawbackAllocation(
        Long id,
        Long masterId,
        Long detailId,
        Long orderId,
        String allocationNo,
        long amountCents,
        Instant createdAt) {
}
