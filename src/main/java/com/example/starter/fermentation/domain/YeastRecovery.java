package com.example.starter.fermentation.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 酵母回收记录（回收追溯链）。每单元每次回收仅一条，由批次维度唯一约束保证失败批次也只能回收一次。
 *
 * @param id              记录 ID
 * @param unitCode        酵母单元编号
 * @param batchId         发起回收的批次 ID
 * @param recoveredAt     回收受理时刻（业务时间）
 * @param viability       回收活率，百分数，如 92.50 表示 92.50%；恰 90.00 合格
 * @param contaminated    是否检出污染：true 污染 / false 无污染
 * @param qualified       回收是否合格：活率 ≥ 90% 且无污染为合格
 * @param generationAfter 回收后的代次（合格时代次加 1，报废时代次不变）
 * @param statusAfter     回收后单元状态：合格转 AVAILABLE，不合格转 SCRAPPED
 */
public record YeastRecovery(
        long id,
        String unitCode,
        long batchId,
        LocalDateTime recoveredAt,
        BigDecimal viability,
        boolean contaminated,
        boolean qualified,
        int generationAfter,
        YeastStatus statusAfter) {
}
