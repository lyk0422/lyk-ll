package com.example.starter.radio.model;

import com.example.starter.radio.domain.DispensingStatus;

/**
 * 分装批次。dispensedActivityBq 为当前有效版本推算至给药时刻的分装活度（Bq）；
 * version 从 1 开始，给药时刻变更生效后加 1；
 * administrationTimeEpoch 为当前有效版本给药时刻（UTC epoch 秒），随变更同事务替换；
 * 可空字段为 NULL 时表示对应步骤尚未发生。
 */
public record Dispensing(
        long id,
        String requestId,
        String fingerprint,
        long motherLiquorId,
        long prescriptionId,
        long calibratorId,
        int version,
        long administrationTimeEpoch,
        Double dispensedActivityBq,
        Double deviationRatio,
        DispensingStatus status,
        String firstChecker,
        String secondChecker,
        String dispenseRequestId,
        String dispenseFingerprint,
        Long dispensedAtEpoch) {
}
