package com.example.starter.support;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 业务时钟，固定 Asia/Shanghai 时区；测试中可拨动到指定时刻以验证 10 分钟边界。
 */
public class ServiceClock {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private volatile Clock clock = Clock.system(ZONE);

    public Instant now() {
        return clock.instant();
    }

    public ZoneId zone() {
        return ZONE;
    }

    /** 测试用：将时钟固定到某一时刻。 */
    public void pinAt(Instant instant) {
        this.clock = Clock.fixed(instant, ZONE);
    }

    /** 测试用：按给定本地日期时间（Asia/Shanghai）固定时钟。 */
    public void pinAtLocal(java.time.LocalDateTime localDateTime) {
        this.clock = Clock.fixed(localDateTime.toInstant(ZoneOffset.ofHours(8)), ZONE);
    }
}
