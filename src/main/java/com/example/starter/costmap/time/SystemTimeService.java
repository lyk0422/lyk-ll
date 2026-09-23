package com.example.starter.costmap.time;

import org.springframework.stereotype.Service;

/**
 * 系统墙钟实现。
 */
@Service
public class SystemTimeService implements TimeService {

    @Override
    public long nowMillis() {
        return System.currentTimeMillis();
    }
}
