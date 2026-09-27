package com.example.starter.fermentation.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 酵母回收记录（投酵回收追溯链的一环）。
 *
 * @param id               回收记录 ID
 * @param yeastUnitId      酵母单元 ID
 * @param batchId          回收时绑定的批次 ID，一批次仅允许回收一次
 * @param recoveredAt      回收时刻
 * @param viabilityPercent 回收活率，单位 %；不低于 90 且无污染判定合格（恰 90 合格）
 * @param contaminated     是否检出污染；污染即报废
 * @param outcome          回收结果：RECYCLED 合格转可用（代次+1） / RETIRED 报废
 * @param generationAfter  回收后酵母代次
 */
public record YeastRecovery(
        long id,
        long yeastUnitId,
        long batchId,
        LocalDateTime recoveredAt,
        BigDecimal viabilityPercent,
        boolean contaminated,
        String outcome,
        int generationAfter) {

    /** 回收结果：合格转可用。 */
    public static final String OUTCOME_RECYCLED = "RECYCLED";
    /** 回收结果：报废。 */
    public static final String OUTCOME_RETIRED = "RETIRED";
}
