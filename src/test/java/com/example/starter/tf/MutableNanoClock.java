package com.example.starter.tf;

import com.example.starter.tf.domain.NanoClock;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 测试用可控纳秒时钟，默认固定时刻，可手动推进。
 */
public class MutableNanoClock implements NanoClock {

    private final AtomicLong now = new AtomicLong(1_000_000_000L);

    @Override
    public long nowNanos() {
        return now.get();
    }

    public void setNanos(long value) {
        now.set(value);
    }

    public void advanceNanos(long delta) {
        now.addAndGet(delta);
    }
}
