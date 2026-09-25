package com.example.starter.torque;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * 统一时间来源，默认使用 UTC 系统时钟；测试可替换为可控时钟。
 * 工具“当日有效”的日界按 UTC 判定。
 */
@Component
public class ServiceClock {

    private volatile Clock clock = Clock.systemUTC();

    /** 替换底层时钟，仅供测试控制时间使用。 */
    public void setClock(Clock clock) {
        this.clock = clock;
    }

    public Instant now() {
        return clock.instant();
    }

    /** 当前 UTC 日期，用于力矩工具日校验判定。 */
    public LocalDate todayUtc() {
        return clock.instant().atOffset(ZoneOffset.UTC).toLocalDate();
    }
}
