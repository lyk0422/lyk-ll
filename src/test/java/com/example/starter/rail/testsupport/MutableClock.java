package com.example.starter.rail.testsupport;

import com.example.starter.rail.service.TimeSource;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 可控时钟：测试可设定/推进当前 UTC epoch 毫秒。
 */
public class MutableClock implements TimeSource {

    private final AtomicLong current = new AtomicLong(0);

    public void set(long epochMillis) {
        current.set(epochMillis);
    }

    public void advance(long deltaMillis) {
        current.addAndGet(deltaMillis);
    }

    @Override
    public long nowMillis() {
        return current.get();
    }
}
