package com.example.starter.arbitration.service;

import org.springframework.stereotype.Component;

/**
 * 基于系统墙钟的默认时钟实现。
 */
@Component
public class SystemClock implements ControllableClock {

    @Override
    public long now() {
        return System.currentTimeMillis();
    }
}
