package com.example.starter.costmap.support;

import com.example.starter.costmap.time.TimeService;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 测试用可控时钟：可显式设置/拨动当前时刻，用于 TTL 边界的确定性验证。
 */
public class MutableTimeService implements TimeService {

    private final AtomicLong now = new AtomicLong(1_000L);

    @Override
    public long nowMillis() {
        return now.get();
    }

    public void setNow(long millis) {
        now.set(millis);
    }

    public void advance(long deltaMillis) {
        now.addAndGet(deltaMillis);
    }
}
