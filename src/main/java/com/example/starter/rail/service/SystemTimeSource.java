package com.example.starter.rail.service;

import org.springframework.stereotype.Component;

/**
 * 生产环境系统 UTC 时钟。
 */
@Component
public class SystemTimeSource implements TimeSource {

    @Override
    public long nowMillis() {
        return System.currentTimeMillis();
    }
}
