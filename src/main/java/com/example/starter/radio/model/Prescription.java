package com.example.starter.radio.model;

/**
 * 处方版本（合成数据）。prescribedActivityBq 对应给药时刻 administrationTimeEpoch（UTC epoch 秒）。
 * 给药时刻变更以新增版本行实现：同一处方族共享 rootPrescriptionId，versionNo 从 1 开始递增；
 * 旧版本行永久保留作为变更证据，不就地覆盖。
 */
public record Prescription(
        long id,
        String code,
        long nuclideId,
        double prescribedActivityBq,
        long administrationTimeEpoch,
        int versionNo,
        Long rootPrescriptionId) {
}
