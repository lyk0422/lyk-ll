package com.example.starter.arbitration.testsupport;

import com.example.starter.arbitration.service.ControllableClock;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 测试用可控时钟：手动设置/推进时间，避免真实休眠。
 */
public class MutableClock implements ControllableClock {

    private final AtomicLong current = new AtomicLong(1_700_000_000_000L);

    @Override
    public long now() {
        return current.get();
    }

    public void set(long epochMs) {
        current.set(epochMs);
    }

    public void advance(long deltaMs) {
        current.addAndGet(deltaMs);
    }
}
