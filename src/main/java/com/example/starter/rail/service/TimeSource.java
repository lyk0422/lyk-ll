package com.example.starter.rail.service;

/**
 * 时间来源抽象，全部返回 UTC epoch 毫秒；测试可替换为可控时钟。
 */
public interface TimeSource {

    long nowMillis();
}
