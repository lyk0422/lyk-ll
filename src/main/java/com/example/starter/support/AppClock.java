package com.example.starter.support;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.springframework.stereotype.Component;

/**
 * 应用时钟：生产环境使用 Asia/Shanghai 系统时钟；测试可替换为固定时钟以控制时间相关判定。
 */
@Component
public class AppClock {

    /** 业务时区：Asia/Shanghai。 */
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private volatile Clock clock = Clock.system(ZONE);

    /**
     * 返回当前本地时间（Asia/Shanghai）。
     */
    public LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    /**
     * 返回当前 UTC 时间；发电车调度（下发/截止/到场/占用）统一使用 UTC 精确时刻。
     * 与 {@link #now()} 基于同一底层时钟，测试固定时钟时两者为同一时刻的不同时区表达。
     */
    public LocalDateTime nowUtc() {
        return LocalDateTime.now(clock.withZone(ZoneOffset.UTC));
    }

    /**
     * 替换底层时钟，仅供测试使用。
     */
    public void setClock(Clock clock) {
        this.clock = clock;
    }

    /**
     * 恢复为系统时钟。
     */
    public void reset() {
        this.clock = Clock.system(ZONE);
    }
}
