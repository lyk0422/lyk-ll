package com.example.starter.water.support;

import java.time.Instant;

import org.springframework.stereotype.Component;

/**
 * 统一时间来源，业务一律使用 UTC 时间；测试中可替换为固定时钟以验证竞态与边界。
 */
@Component
public class TimeProvider {

    /**
     * 返回当前 UTC 时刻。
     */
    public Instant now() {
        return Instant.now();
    }
}
