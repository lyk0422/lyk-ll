package com.example.starter.radio.model;

/**
 * 母液批次。initialActivityBq 对应 referenceTimeEpoch（UTC epoch 秒）；
 * remainingActivityBq 对应 remainingTimeEpoch，发药扣减在同一事务内更新。
 */
public record MotherLiquor(
        long id,
        String batchCode,
        long nuclideId,
        double initialActivityBq,
        long referenceTimeEpoch,
        double remainingActivityBq,
        long remainingTimeEpoch,
        String status) {
}
