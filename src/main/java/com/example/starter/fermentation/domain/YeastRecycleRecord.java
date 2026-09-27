package com.example.starter.fermentation.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 酵母回收追溯记录。每个绑定批次仅允许回收一次。
 *
 * @param id               记录 ID
 * @param yeastUnitId      酵母单元 ID
 * @param batchId          投酵绑定的批次 ID
 * @param generationBefore 回收前代次
 * @param generationAfter  回收后代次（合格加 1，报废不变）
 * @param viabilityPercent 回收活率，单位 %；恰 90.00 合格
 * @param contaminated     污染判定：有污染一律报废
 * @param result           回收结果
 * @param recoveredAt      回收时刻（本地时间）
 */
public record YeastRecycleRecord(
        long id,
        long yeastUnitId,
        long batchId,
        int generationBefore,
        int generationAfter,
        BigDecimal viabilityPercent,
        boolean contaminated,
        RecycleResult result,
        LocalDateTime recoveredAt) {
}
