package com.example.starter.radio.model;

import com.example.starter.radio.domain.DispensingStatus;

/**
 * 分装批次。dispensedActivityBq 为当前有效版本推算至给药时刻的分装活度（Bq）；
 * version 为当前有效计算版本号（从 1 开始）；administrationTimeEpoch 为当前有效版本给药时刻（UTC epoch 秒）；
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
        long version,
        Long administrationTimeEpoch,
        DispensingStatus status,
        String firstChecker,
        String secondChecker,
        String dispenseRequestId,
        String dispenseFingerprint,
        Long dispensedAtEpoch) {
}
