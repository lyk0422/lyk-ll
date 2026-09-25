package com.example.starter.deicing.support;

/**
 * 可控时钟：业务代码一律通过本接口取当前时刻，测试中可设定固定时间。
 */
public interface ServiceClock {

    /**
     * @return 当前时刻（UTC epoch 毫秒）
     */
    long nowMillis();
}
