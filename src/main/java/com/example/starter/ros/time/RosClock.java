package com.example.starter.ros.time;

/**
 * 可控时钟抽象，便于测试时间相关逻辑。
 */
public interface RosClock {
    long nowMillis();
}
