package com.example.starter.radio.model;

import com.example.starter.radio.domain.DispensingStatus;

/**
 * 分装批次。dispensedActivityBq 为推算至给药时刻的分装活度（Bq）；
 * 可空字段为 NULL 时表示对应步骤尚未发生。
 */
public record Dispensing(
        long id,
        String requestId,
        String fingerprint,
        long motherLiquorId,
        long prescriptionId,
        long calibratorId,
        Double dispensedActivityBq,
        Double deviationRatio,
        DispensingStatus status,
        String firstChecker,
        String secondChecker,
        String dispenseRequestId,
        String dispenseFingerprint,
        Long dispensedAtEpoch) {
}
