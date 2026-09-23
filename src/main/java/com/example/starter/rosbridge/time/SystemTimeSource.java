package com.example.starter.rosbridge.time;

import org.springframework.stereotype.Component;

/** 系统墙钟。 */
@Component
public class SystemTimeSource implements TimeSource {

    @Override
    public long nowMillis() {
        return System.currentTimeMillis();
    }
}
