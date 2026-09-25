package com.example.starter.cutover.support;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 时间来源，业务一律通过该组件取当前时间；测试替换为可调时钟以精确验证超时/窗口边界。
 */
public class TimeSource {

    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final Clock clock;

    public TimeSource(Clock clock) {
        this.clock = clock;
    }

    public LocalDateTime now() {
        return LocalDateTime.ofInstant(Instant.now(clock), ZONE);
    }

    public Clock getClock() {
        return clock;
    }
}
