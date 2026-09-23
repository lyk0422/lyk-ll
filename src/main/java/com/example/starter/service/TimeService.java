package com.example.starter.service;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

/**
 * 统一时钟，测试可替换为固定时钟以验证到期边界。
 */
@Component
public class TimeService {

    private volatile Clock clock = Clock.systemUTC();

    public Instant now() {
        return Instant.now(clock);
    }

    /** 仅供测试替换时钟。 */
    public void setClock(Clock clock) {
        this.clock = clock;
    }
}
