package com.example.starter.radio.model;

/**
 * 发药勾稽台账：母液初始活度 = 分装总量 + 剩余活度 + 衰变残量（均推算至 measuredAtEpoch，UTC epoch 秒）。
 */
public record ReconciliationLedger(
        long id,
        long dispensingId,
        double initialActivityBq,
        double dispensedTotalBq,
        double remainingActivityBq,
        double decayResidualBq,
        boolean balanced,
        long measuredAtEpoch) {
}
