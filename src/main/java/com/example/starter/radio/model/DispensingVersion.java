package com.example.starter.radio.model;

/**
 * 分装计算版本证据。重算始终基于母液初始活度快照（motherInitialActivityBq）
 * 与母液参考时刻快照（motherReferenceTimeEpoch）及合成核素半衰期快照（halfLifeSeconds），
 * 不受随后母液剩余量变化影响。dispensedActivityBq 为推算至该版本给药时刻的活度（Bq），超差为 null。
 */
public record DispensingVersion(
        long id,
        long dispensingId,
        long versionNo,
        long administrationTimeEpoch,
        Double dispensedActivityBq,
        Double deviationRatio,
        double motherInitialActivityBq,
        long motherReferenceTimeEpoch,
        long halfLifeSeconds,
        String source,
        long createdAtEpoch) {
}
