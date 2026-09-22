package com.example.starter.tf.domain;

/**
 * 可替换的 epoch 纳秒时钟；生产用系统时钟，时间相关测试用可控时钟。
 */
public interface NanoClock {

    /** 当前时间，epoch 纳秒。 */
    long nowNanos();
}
