package com.example.rebate.domain;

import java.time.Instant;

/**
 * 追缴明细：冲回单批准时在同一事务内产生的独立明细。
 *
 * @param reversalId 所属冲回单 id（一张已批准冲回单恰好一条）
 * @param clawbackSerial 追缴独立流水号，与原核销国库流水互不影响
 * @param amountCents 批准追缴金额（正数整数分）
 * @param approvedAt 批准时刻（UTC）
 */
public record ClawbackDetail(
        Long id,
        Long reversalId,
        String clawbackSerial,
        long amountCents,
        Instant approvedAt) {
}
