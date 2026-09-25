package com.example.starter.cems;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;

import org.springframework.stereotype.Component;

/**
 * 可控时钟：默认使用 Asia/Shanghai 系统时钟；测试可替换为固定时钟以验证“恰满 2 小时”等边界。
 */
@Component
public class CemsClock {

    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private Clock clock = Clock.system(ZONE);

    public LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    /** 测试支持：固定到指定时刻。 */
    public void setFixed(LocalDateTime time) {
        this.clock = Clock.fixed(time.atZone(ZONE).toInstant(), ZONE);
    }

    /** 测试支持：恢复系统时钟。 */
    public void reset() {
        this.clock = Clock.system(ZONE);
    }
}
