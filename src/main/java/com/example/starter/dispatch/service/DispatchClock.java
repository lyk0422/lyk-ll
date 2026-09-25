package com.example.starter.dispatch.service;

import java.time.Instant;

/**
 * 可调度的时钟抽象：生产环境返回系统 UTC 时刻，测试可替换为可控时钟。
 */
public interface DispatchClock {

    /**
     * 当前时刻（UTC）。
     */
    Instant now();
}
