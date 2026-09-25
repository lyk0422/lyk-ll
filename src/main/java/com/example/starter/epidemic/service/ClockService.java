package com.example.starter.epidemic.service;

import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 可控时钟：默认返回系统 UTC 时间；测试可固定/拨快时间，用于 2 小时/24 小时边界判定。
 */
@Component
public class ClockService {

    private volatile Instant fixed;

    /** 当前时间（UTC）：固定时间优先，否则取系统 UTC。 */
    public Instant now() {
        Instant snapshot = fixed;
        return snapshot != null ? snapshot : Instant.now();
    }

    /** 固定时钟到指定时刻。 */
    public void setFixed(Instant instant) {
        this.fixed = instant;
    }

    /** 恢复系统时钟。 */
    public void reset() {
        this.fixed = null;
    }
}
