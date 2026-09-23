package com.example.starter.costmap;

import org.springframework.stereotype.Component;

import java.util.function.LongSupplier;

/**
 * 可控时钟：默认取系统时间，测试可替换时间源以验证 TTL 边界。
 */
@Component
public class CostmapClock {

    private volatile LongSupplier source = System::currentTimeMillis;

    public long now() {
        return source.getAsLong();
    }

    /**
     * 替换时间源（主要供测试使用）。
     */
    public void setSource(LongSupplier source) {
        this.source = source;
    }

    /**
     * 恢复系统时钟。
     */
    public void reset() {
        this.source = System::currentTimeMillis;
    }
}
