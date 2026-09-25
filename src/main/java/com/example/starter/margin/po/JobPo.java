package com.example.starter.margin.po;

import com.example.starter.margin.domain.JobStatus;
import com.example.starter.margin.domain.LiquidationReason;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 强平作业持久化对象。
 * PENDING 可被达标入账撤销为 CANCELLED；一旦 EXECUTED 即终态不可撤销。
 *
 * @param id         自增主键
 * @param accountNo  账户号
 * @param status     PENDING/CANCELLED/EXECUTED
 * @param reason     BELOW_110（低于 110% 直接强平）/OVERDUE（追保逾期）
 * @param finalRatio 执行后维持担保比例；PENDING/CANCELLED 或负债清零时为 null
 * @param createdAt  生成时间（UTC）
 * @param executedAt 执行时间（UTC）
 */
public record JobPo(
        Long id,
        String accountNo,
        JobStatus status,
        LiquidationReason reason,
        BigDecimal finalRatio,
        Instant createdAt,
        Instant executedAt
) {
}
