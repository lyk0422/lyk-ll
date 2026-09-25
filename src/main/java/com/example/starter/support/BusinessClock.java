package com.example.starter.support;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.springframework.stereotype.Component;

/**
 * 可控时钟：默认使用 Asia/Shanghai 系统时钟；测试可固定或拨动时间，
 * 用于特级用户“恰满 5 分钟”发电车判定等时间相关规则。
 */
@Component
public class BusinessClock {

    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private volatile Clock clock = Clock.system(ZONE);

    public Instant now() {
        return clock.instant();
    }

    public void setClock(Clock clock) {
        this.clock = clock;
    }

    /**
     * 固定到指定时刻（毫秒时间戳）。
     */
    public void freezeAt(long epochMilli) {
        this.clock = Clock.fixed(Instant.ofEpochMilli(epochMilli), ZONE);
    }

    /**
     * 恢复为系统时钟。
     */
    public void reset() {
        this.clock = Clock.system(ZONE);
    }
}
