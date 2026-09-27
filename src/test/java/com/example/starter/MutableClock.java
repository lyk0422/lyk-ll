package com.example.starter;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 测试用可控时钟：默认跟随系统时钟；可固定到指定时刻以验证批次有效期边界
 * （now &lt; 截止有效，恰在截止失效）。以 @Primary 覆盖生产 Clock Bean。
 */
public class MutableClock extends Clock {

    private volatile Clock delegate = Clock.system(ZoneOffset.UTC);

    public void setInstant(Instant instant) {
        this.delegate = Clock.fixed(instant, ZoneId.of("Asia/Shanghai"));
    }

    public void advance(Duration duration) {
        this.delegate = Clock.fixed(delegate.instant().plus(duration), ZoneId.of("Asia/Shanghai"));
    }

    public void reset() {
        this.delegate = Clock.system(ZoneOffset.UTC);
    }

    @Override
    public ZoneId getZone() {
        return delegate.getZone();
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return delegate.withZone(zone);
    }

    @Override
    public Instant instant() {
        return delegate.instant();
    }
}
