package com.example.starter.margin.po;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 强平指令明细持久化对象。指令随执行生成，逐笔按负债从大到小，平到比例恢复至 140% 即止，不得多平。
 *
 * @param id              自增主键
 * @param jobId           所属强平作业
 * @param accountNo       账户号
 * @param liabilityNo     被平负债编号
 * @param seqNo           平仓顺序，从 1 开始
 * @param repaidAmount    该笔负债偿还金额，单位元（允许末笔部分偿还）
 * @param assetsReduced   处置担保物减记的总资产金额，单位元
 * @param ratioAfterPct   平完该笔后的维持担保比例
 * @param createdAt       生成时间（UTC）
 */
public record LiquidationItemPo(
        Long id,
        Long jobId,
        String accountNo,
        String liabilityNo,
        int seqNo,
        BigDecimal repaidAmount,
        BigDecimal assetsReduced,
        BigDecimal ratioAfterPct,
        Instant createdAt
) {
}
