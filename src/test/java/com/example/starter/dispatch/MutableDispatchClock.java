package com.example.starter.dispatch;

import com.example.starter.dispatch.service.DispatchClock;

import java.time.Instant;

/**
 * 测试用可控时钟：可手动设置/推进当前时刻。
 */
public class MutableDispatchClock implements DispatchClock {

    private volatile Instant current;

    public MutableDispatchClock(Instant initial) {
        this.current = initial;
    }

    public void setTime(Instant time) {
        this.current = time;
    }

    public void advanceMillis(long millis) {
        this.current = current.plusMillis(millis);
    }

    @Override
    public Instant now() {
        return current;
    }
}
