package com.example.starter.lot.api;

import com.example.starter.lot.domain.LotStatus;

import java.util.List;

/**
 * 隔离影响面查询结果：某隔离来源沿谱系的全部影响批次及当前生效来源集合。
 *
 * @param sourceLotId 隔离来源批次
 * @param lots        当前仍受该来源影响（含来源自身且其自身隔离未解除）的批次
 */
public record ImpactResponse(
        String sourceLotId,
        List<ImpactedLot> lots
) {

    /**
     * 受影响批次视图。
     *
     * @param lotId                   批次 ID
     * @param status                  当前状态
     * @param activeQuarantineSources 当前仍生效的全部隔离来源
     */
    public record ImpactedLot(String lotId, LotStatus status, List<String> activeQuarantineSources) {
    }
}
