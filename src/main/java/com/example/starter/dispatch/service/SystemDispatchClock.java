package com.example.starter.dispatch.service;

import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 生产时钟：返回系统 UTC 时刻。
 */
@Component
public class SystemDispatchClock implements DispatchClock {

    @Override
    public Instant now() {
        return Instant.now();
    }
}
