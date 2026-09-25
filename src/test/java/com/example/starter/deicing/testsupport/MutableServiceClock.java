package com.example.starter.deicing.testsupport;

import com.example.starter.deicing.support.ServiceClock;

/**
 * 测试用可控时钟：显式设定当前时刻，验证 HOT、延误等时间语义。
 */
public class MutableServiceClock implements ServiceClock {

    private volatile long current;

    /**
     * @param current 当前时刻（UTC epoch 毫秒）
     */
    public void setCurrent(long current) {
        this.current = current;
    }

    /**
     * @param millis 向前推进的毫秒数
     */
    public void advance(long millis) {
        this.current += millis;
    }

    @Override
    public long nowMillis() {
        return current;
    }
}
