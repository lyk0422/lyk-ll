package com.example.starter.sensor.time;

import org.springframework.stereotype.Component;

/**
 * 系统墙钟实现。
 */
@Component
public class SystemServiceClock implements ServiceClock {

    @Override
    public long nowMillis() {
        return System.currentTimeMillis();
    }
}
