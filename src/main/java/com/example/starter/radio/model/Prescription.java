package com.example.starter.radio.model;

/**
 * 处方（合成数据）。prescribedActivityBq 对应给药时刻 administrationTimeEpoch（UTC epoch 秒）。
 */
public record Prescription(
        long id,
        String code,
        long nuclideId,
        double prescribedActivityBq,
        long administrationTimeEpoch) {
}
