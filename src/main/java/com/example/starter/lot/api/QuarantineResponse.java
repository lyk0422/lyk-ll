package com.example.starter.lot.api;

import com.example.starter.lot.domain.LotStatus;

import java.util.List;

/**
 * 隔离/解除结果。
 *
 * @param sourceLotId 被直接操作的批次（隔离来源）
 * @param action      QUARANTINE / RELEASE
 * @param affected    受本次提交影响的批次及其提交后状态与生效来源集合
 */
public record QuarantineResponse(
        String sourceLotId,
        QuarantineRequest.Action action,
        List<AffectedLot> affected
) {

    /**
     * 受影响批次视图。
     *
     * @param lotId                   批次 ID
     * @param status                  本次提交后的状态
     * @param activeQuarantineSources 当前仍生效的隔离来源集合
     */
    public record AffectedLot(String lotId, LotStatus status, List<String> activeQuarantineSources) {
    }
}
