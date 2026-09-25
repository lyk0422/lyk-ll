package com.example.starter.lc.support;

import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * 统一时间来源，默认使用 UTC 系统时钟；测试可替换为可控时钟以验证 5 个工作日边界。
 */
@Component
public class ServiceClock {

    private volatile Clock clock = Clock.systemUTC();

    public Instant now() {
        return Instant.now(clock);
    }

    /** 仅供测试替换时钟，生产环境不调用。 */
    public void setClock(Clock clock) {
        this.clock = clock;
    }

    /** 仅供测试恢复系统时钟。 */
    public void reset() {
        this.clock = Clock.systemUTC();
    }
}
