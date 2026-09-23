package com.example.starter.ros.time;

import org.springframework.stereotype.Component;

/** 基于系统时钟的默认实现。 */
@Component
public class SystemRosClock implements RosClock {
    @Override
    public long nowMillis() {
        return System.currentTimeMillis();
    }
}
