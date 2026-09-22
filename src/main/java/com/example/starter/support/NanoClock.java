package com.example.starter.support;

import org.springframework.stereotype.Component;

/**
 * 纳秒时钟组件，便于单元测试注入可控时间；默认委托 {@link System#nanoTime()}。
 */
@Component
public class NanoClock {

    /**
     * 返回当前 JVM 内单调纳秒时间。
     */
    public long nanoTime() {
        return System.nanoTime();
    }
}
