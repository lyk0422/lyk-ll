package com.example.starter.radio.model;

import com.example.starter.radio.domain.DispensingVersionStatus;

/**
 * 分装计算版本证据。每个版本固化给药时刻、重算活度、偏差及母液/核素快照，
 * 可按衰变公式由快照精确复算；旧版本 SUPERSEDED 后仍永久保留。
 * changeOrderId 为来源变更单主键，INITIAL 初算时为 null。
 */
public record DispensingVersion(
        long id,
        long dispensingId,
        int version,
        long administrationTimeEpoch,
        Double dispensedActivityBq,
        Double deviationRatio,
        long motherLiquorId,
        double motherInitialActivityBq,
        long motherReferenceTimeEpoch,
        long halfLifeSeconds,
        String source,
        Long changeOrderId,
        DispensingVersionStatus status,
        long createdAtEpoch) {
}
