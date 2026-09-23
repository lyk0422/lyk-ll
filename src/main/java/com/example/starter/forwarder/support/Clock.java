package com.example.starter.forwarder.support;

/**
 * 可控时钟，测试可固定/推进时间，生产环境使用系统时间。
 */
public interface Clock {

    long nowMillis();
}
