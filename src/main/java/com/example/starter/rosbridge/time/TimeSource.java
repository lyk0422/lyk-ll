package com.example.starter.rosbridge.time;

/**
 * 可控时钟，便于测试超时与宽限期。
 */
public interface TimeSource {

    long nowMillis();
}
