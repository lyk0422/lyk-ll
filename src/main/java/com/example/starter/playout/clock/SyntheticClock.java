package com.example.starter.playout.clock;

import java.time.Instant;

/**
 * 可注入的合成时钟；测试与本地演示可显式设定时间，生产环境不接真实广电设备。
 */
public class SyntheticClock {

    private volatile Instant now = Instant.parse("2026-01-01T00:00:00Z");

    public Instant instant() {
        return now;
    }

    public void setInstant(Instant instant) {
        this.now = instant;
    }
}
