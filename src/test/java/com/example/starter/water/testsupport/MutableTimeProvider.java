package com.example.starter.water.testsupport;

import java.time.Instant;

import com.example.starter.water.support.TimeProvider;

/**
 * 可控时钟：测试中显式推进时间，用于封存时刻、限取生效边界与竞态排序。
 */
public class MutableTimeProvider extends TimeProvider {

    private volatile Instant current;

    public MutableTimeProvider(Instant initial) {
        this.current = initial;
    }

    @Override
    public Instant now() {
        return current;
    }

    public void set(Instant instant) {
        this.current = instant;
    }

    public void advanceSeconds(long seconds) {
        this.current = current.plusSeconds(seconds);
    }
}
