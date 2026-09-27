package com.example.starter.fermentation.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 酵母回收记录：保存活率、污染判定与回收结论，用于回收追溯。
 * 每个发酵批次仅允许回收一次（含失败判定），由 uk_recovery_batch 兜底。
 *
 * @param id              记录 ID
 * @param yeastUnitCode   被回收的酵母单元编号
 * @param batchId         发起回收的批次 ID
 * @param viabilityPercent 回收活率（百分比，恰 90.00 合格）
 * @param contaminated    是否检出污染
 * @param qualified       回收是否合格：合格转可用且代次 +1，不合格报废
 * @param generationAfter 回收后的单元代次
 * @param unitVersionAfter 回收后的酵母单元乐观版本
 * @param recoveredAt     回收受理的业务时刻
 * @param createdAt       记录创建时间
 */
public record YeastRecoveryRecord(
        long id,
        String yeastUnitCode,
        long batchId,
        BigDecimal viabilityPercent,
        boolean contaminated,
        boolean qualified,
        int generationAfter,
        int unitVersionAfter,
        LocalDateTime recoveredAt,
        LocalDateTime createdAt) {
}
