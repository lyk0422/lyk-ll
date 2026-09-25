package com.example.starter.bonded.support;

import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 统一时间源，默认返回当前 UTC 时间；测试中可替换为可控时钟以验证 48 小时边界。
 */
@Component
public class TimeProvider {

    /**
     * 当前 UTC 时间。
     */
    public Instant now() {
        return Instant.now();
    }
}
