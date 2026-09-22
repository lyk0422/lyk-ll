package com.example.starter.sensor.time;

/**
 * 可控墙钟抽象，便于单元测试确定到达时刻。
 */
public interface ServiceClock {

    /** 当前墙钟时刻，epoch 毫秒。 */
    long nowMillis();
}
