package com.example.starter.forwarder.testsupport;

import com.example.starter.forwarder.support.Clock;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 可控测试时钟：可固定起点并手动推进，用于 in-flight 超时断言。
 */
public class MutableClock implements Clock {

    private final AtomicLong now = new AtomicLong(1_000_000L);

    @Override
    public long nowMillis() {
        return now.get();
    }

    public void advance(long deltaMillis) {
        now.addAndGet(deltaMillis);
    }
}
