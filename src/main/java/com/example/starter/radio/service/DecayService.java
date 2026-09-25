package com.example.starter.radio.service;

import org.springframework.stereotype.Service;

/**
 * 放射性衰变计算：A(t) = A0 * 2^(-(t - t0) / T)。
 * 半衰期参数一律取自合成核素表，本类只提供纯函数，全部计算可复算。
 */
@Service
public class DecayService {

    /**
     * 将活度从 fromEpoch 推算至 toEpoch（均为 UTC epoch 秒）。
     * toEpoch 早于 fromEpoch 时按同一公式反推（活度增大）。
     *
     * @param activityAtFrom   fromEpoch 时刻活度，单位 Bq
     * @param fromEpoch        标定时刻，UTC epoch 秒
     * @param toEpoch          目标时刻，UTC epoch 秒
     * @param halfLifeSeconds  半衰期，单位秒，必须为正
     * @return toEpoch 时刻活度，单位 Bq
     */
    public double activityAt(double activityAtFrom, long fromEpoch, long toEpoch, long halfLifeSeconds) {
        if (halfLifeSeconds <= 0) {
            throw new IllegalArgumentException("halfLifeSeconds must be positive");
        }
        double elapsedHalves = (double) (toEpoch - fromEpoch) / (double) halfLifeSeconds;
        return activityAtFrom * Math.pow(0.5, elapsedHalves);
    }

    /**
     * 衰变残量：初始活度在 [fromEpoch, toEpoch] 区间内因衰变损失的部分，单位 Bq。
     */
    public double decayResidual(double initialActivity, long fromEpoch, long toEpoch, long halfLifeSeconds) {
        return initialActivity - activityAt(initialActivity, fromEpoch, toEpoch, halfLifeSeconds);
    }
}
