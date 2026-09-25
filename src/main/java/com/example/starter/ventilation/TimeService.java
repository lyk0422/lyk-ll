package com.example.starter.ventilation;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 统一时间来源：生产使用系统时钟，测试可替换为可控时钟以验证 60 秒恰超时。
 * 全部时间以 Asia/Shanghai 落库（DATETIME 不带时区）。
 */
public class TimeService {

    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private Clock clock = Clock.system(ZONE);

    public LocalDateTime now() {
        return LocalDateTime.ofInstant(Instant.now(clock), ZONE);
    }

    /** 仅供测试替换时钟。 */
    public void setClock(Clock clock) {
        this.clock = clock;
    }
}
