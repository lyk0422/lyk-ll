package com.example.starter.rosbag.time;

import org.springframework.stereotype.Component;

/**
 * 基于墙钟的默认时间源：毫秒级精度放大到纳秒。
 */
@Component
public class SystemTimeSource implements TimeSource {

    @Override
    public long nowEpochNs() {
        return System.currentTimeMillis() * 1_000_000L;
    }
}
