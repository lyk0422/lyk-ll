package com.example.starter.costmap.support;

import org.springframework.stereotype.Component;

/**
 * 可控时间源：生产环境返回系统时钟；测试可显式设置当前毫秒，避免对真实休眠的依赖。
 */
@Component
public class TimeSource {

    private Long fixedMs;

    /** 当前业务时间，epoch 毫秒。 */
    public long nowMs() {
        return fixedMs != null ? fixedMs : System.currentTimeMillis();
    }

    /** 测试专用：固定时钟。 */
    public void setFixed(long ms) {
        this.fixedMs = ms;
    }

    /** 测试专用：推进固定时钟。 */
    public void advance(long deltaMs) {
        if (fixedMs == null) {
            throw new IllegalStateException("clock is not fixed");
        }
        this.fixedMs += deltaMs;
    }

    /** 恢复系统时钟。 */
    public void reset() {
        this.fixedMs = null;
    }
}
