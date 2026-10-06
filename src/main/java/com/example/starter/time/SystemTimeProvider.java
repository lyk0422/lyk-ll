package com.example.starter.time;

import java.time.Clock;
import java.time.LocalDate;

import org.springframework.stereotype.Component;

/**
 * 基于 UTC 系统时钟的默认业务日期提供者。
 */
@Component
public class SystemTimeProvider implements TimeProvider {

    private final Clock clock = Clock.systemUTC();

    @Override
    public LocalDate today() {
        return LocalDate.now(clock);
    }
}
