package com.example.starter.cems;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.springframework.stereotype.Component;

/**
 * 可控时钟：默认使用 Asia/Shanghai 系统时钟；测试可替换为固定时钟以验证“恰满 2 小时”等边界。
 * 雨季溢流域（B 题）使用独立的 UTC 时钟，与旧域互不影响，测试可单独固定。
 */
@Component
public class CemsClock {

    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private Clock clock = Clock.system(ZONE);
    private Clock utcClock = Clock.systemUTC();

    public LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    /** 溢流域当前 UTC 墙钟时间。 */
    public LocalDateTime nowUtc() {
        return LocalDateTime.now(utcClock);
    }

    /** 测试支持：固定到指定时刻。 */
    public void setFixed(LocalDateTime time) {
        this.clock = Clock.fixed(time.atZone(ZONE).toInstant(), ZONE);
    }

    /** 测试支持：把溢流域 UTC 时钟固定到指定 UTC 时刻。 */
    public void setFixedUtc(LocalDateTime time) {
        this.utcClock = Clock.fixed(time.atZone(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);
    }

    /** 测试支持：恢复系统时钟。 */
    public void reset() {
        this.clock = Clock.system(ZONE);
    }

    /** 测试支持：恢复 UTC 系统时钟。 */
    public void resetUtc() {
        this.utcClock = Clock.systemUTC();
    }
}
