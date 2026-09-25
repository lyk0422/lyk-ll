package com.example.starter.decay;

import java.time.Duration;
import java.time.Instant;

import org.springframework.stereotype.Service;

/**
 * 放射性衰变计算：A(t) = A0 * 2^(-(t - t0) / T)，半衰期一律取合成核素表。
 * 计算可复算：相同输入必然得到相同输出。
 */
@Service
public class DecayService {

    /**
     * 将活度从 from 时刻推算到 to 时刻（UTC）。
     *
     * @param activityMbq     起始活度，单位 MBq
     * @param from            起始活度测量时刻（UTC）
     * @param to              目标时刻（UTC）
     * @param halfLifeSeconds 半衰期，单位秒
     * @return 目标时刻活度，单位 MBq
     */
    public double decayTo(double activityMbq, Instant from, Instant to, long halfLifeSeconds) {
        double elapsedSeconds = Duration.between(from, to).toNanos() / 1_000_000_000.0;
        return activityMbq * Math.pow(2.0, -elapsedSeconds / halfLifeSeconds);
    }
}
