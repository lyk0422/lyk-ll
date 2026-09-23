package com.example.starter.costmap.time;

/**
 * 可控时钟抽象：生产环境返回系统时钟，测试中可固定/拨动，保证 TTL 边界可确定性验证。
 */
public interface TimeService {

    /** 当前时刻（epoch 毫秒，UTC 绝对时刻）。 */
    long nowMillis();
}
