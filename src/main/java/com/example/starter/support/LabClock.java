package com.example.starter.support;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import org.springframework.stereotype.Component;

/**
 * 统一时间来源，生产环境使用 Asia/Shanghai 系统时钟；
 * 测试可替换为固定或可控时钟，以便验证时间不倒退规则。
 */
@Component
public class LabClock {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private volatile Clock clock = Clock.system(ZONE);

    /**
     * 当前时间。
     */
    public Instant now() {
        return Instant.now(clock);
    }

    /**
     * 替换底层时钟，仅供测试使用。
     */
    public void setClock(Clock clock) {
        this.clock = clock;
    }
}
