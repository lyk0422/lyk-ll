package com.example.starter.service;

import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 业务时钟：默认返回系统 UTC 当前时刻；测试可将时钟固定到精确时刻，
 * 用于验证“恰到自检截止即失效”等时间边界，避免用休眠充当验证。
 */
@Component
public class BusinessClock {

    /** 非空表示时钟被固定到该时刻（仅测试使用）；为空时取系统 UTC。 */
    private volatile Instant fixed;

    /** 当前 UTC 时刻。 */
    public Instant now() {
        Instant pinned = fixed;
        return pinned != null ? pinned : Instant.now();
    }

    /** 将时钟固定到指定 UTC 时刻（测试用）。 */
    public void pin(Instant instant) {
        this.fixed = instant;
    }

    /** 恢复系统 UTC 时钟（测试用）。 */
    public void reset() {
        this.fixed = null;
    }
}
