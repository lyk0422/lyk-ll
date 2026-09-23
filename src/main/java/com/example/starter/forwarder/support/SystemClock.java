package com.example.starter.forwarder.support;

import org.springframework.stereotype.Component;

/**
 * 生产环境系统时钟。
 */
@Component
public class SystemClock implements Clock {

    @Override
    public long nowMillis() {
        return System.currentTimeMillis();
    }
}
