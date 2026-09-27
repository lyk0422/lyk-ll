package com.example.starter.radio.model;

import com.example.starter.radio.domain.DispensingStatus;

/**
 * 分装批次。dispensedActivityBq 为当前有效版本推算至给药时刻的分装活度（Bq）；
 * 可空字段为 NULL 时表示对应步骤尚未发生。
 * versionNo 为当前有效计算版本号（从 1 开始，变更生效加 1）；
 * activeChangeOrderId 非空表示存在挂起候选，非空期间原版本仍可合法发药。
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
        Long dispensedAtEpoch,
        int versionNo,
        Long activeChangeOrderId) {
}
