package com.example.starter.pump.core;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * 系统时钟实现，默认 Asia/Shanghai。
 */
@Component
public class SystemTimeProvider implements TimeProvider {

    private volatile Clock clock = Clock.system(TimeProvider.ZONE);

    @Override
    public LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    @Override
    public Clock clock() {
        return clock;
    }
}
