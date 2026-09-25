package com.example.starter.support;

import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 生产环境时钟，直接读取系统时间。
 */
@Component
public class SystemClock implements Clock {

    @Override
    public Instant now() {
        return Instant.now();
    }
}
