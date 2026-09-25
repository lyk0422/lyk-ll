package com.example.starter.margin.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 强平作业执行/查询响应。
 *
 * @param jobId      作业 ID
 * @param accountNo  账户号
 * @param status     PENDING/CANCELLED/EXECUTED
 * @param reason     BELOW_110/OVERDUE
 * @param items      强平指令明细（按负债从大到小，平到比例恢复至 140% 即止）
 * @param finalRatio 执行后维持担保比例
 * @param executedAt 执行时间（UTC）
 */
public record LiquidationResponse(
        Long jobId,
        String accountNo,
        String status,
        String reason,
        List<Item> items,
        BigDecimal finalRatio,
        Instant executedAt
) {
    /**
     * 单笔强平指令。
     *
     * @param seqNo          平仓顺序（从 1 开始）
     * @param liabilityNo    被平负债编号
     * @param repaidAmount   偿还该笔负债金额（元）
     * @param assetsReduced  处置担保物减记资产金额（元）
     * @param ratioAfterPct  平完该笔后的维持担保比例
     */
    public record Item(
            int seqNo,
            String liabilityNo,
            BigDecimal repaidAmount,
            BigDecimal assetsReduced,
            BigDecimal ratioAfterPct
    ) {
    }
}
