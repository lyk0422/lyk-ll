package com.example.rebate.support;

import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * 统一时间源。生产环境返回系统 UTC 时间；测试可注入固定时刻以验证按 UTC 日界取文库版本。
 */
@Component
public class TimeService {

    private volatile Instant fixed;

    /**
     * 返回当前时刻（UTC）；测试固定后始终返回固定时刻。
     */
    public Instant now() {
        Instant f = fixed;
        return f != null ? f : Instant.now();
    }

    /**
     * 仅供测试：固定时钟。
     */
    public void setFixed(Instant instant) {
        this.fixed = instant;
    }

    /**
     * 仅供测试：恢复系统时钟。
     */
    public void reset() {
        this.fixed = null;
    }
}
