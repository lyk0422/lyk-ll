package com.example.starter.tf.time;

import com.example.starter.tf.domain.NanoClock;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 基于系统 UTC 时间的纳秒时钟。
 */
@Component
public class SystemNanoClock implements NanoClock {

    @Override
    public long nowNanos() {
        Instant now = Instant.now();
        return Math.addExact(Math.multiplyExact(now.getEpochSecond(), 1_000_000_000L), now.getNano());
    }
}
