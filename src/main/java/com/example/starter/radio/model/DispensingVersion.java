package com.example.starter.radio.model;

/**
 * 分装计算版本证据（不可变）。每个版本记录其依据的处方版本、给药时刻、推算活度与偏差。
 * changeOrderId 为 NULL 表示初始计算版本；非空表示由该变更单生效产生的新版本。
 */
public record DispensingVersion(
        long id,
        long dispensingId,
        int versionNo,
        long prescriptionId,
        long administrationTimeEpoch,
        Double dispensedActivityBq,
        Double deviationRatio,
        String calcStatus,
        Long changeOrderId,
        long createdAtEpoch) {
}
