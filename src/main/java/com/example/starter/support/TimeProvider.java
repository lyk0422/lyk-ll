package com.example.starter.support;

import org.springframework.stereotype.Component;

/**
 * 系统时间来源；测试可替换为可控时钟。
 */
@Component
public class TimeProvider {

    /**
     * @return 当前时间，Unix 毫秒时间戳（UTC）
     */
    public long nowMillis() {
        return System.currentTimeMillis();
    }
}
