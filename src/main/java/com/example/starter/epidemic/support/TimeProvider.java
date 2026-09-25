package com.example.starter.epidemic.support;

import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * 可控时钟：生产环境使用系统 UTC 时钟，测试可替换为固定时钟。
 * 报告时限、提交时刻、审核轨迹等全部以此时钟为准。
 */
@Component
public class TimeProvider {

    private volatile Clock clock = Clock.systemUTC();

    /** 替换底层时钟（主要用于测试）。 */
    public void setClock(Clock clock) {
        this.clock = clock;
    }

    public java.time.OffsetDateTime nowOffsetDateTime() {
        return java.time.OffsetDateTime.now(clock);
    }

    public java.time.LocalDateTime nowUtc() {
        return java.time.LocalDateTime.now(clock);
    }
}
