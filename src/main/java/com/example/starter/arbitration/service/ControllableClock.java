package com.example.starter.arbitration.service;

/**
 * 可控时钟，便于测试时戳单调性、时钟回拨与过期判定。
 */
public interface ControllableClock {

    /** 当前时间（UTC epoch 毫秒）。 */
    long now();
}
