package com.example.starter.support;

import java.time.Instant;

/**
 * 可控时钟：生产环境返回系统 UTC 时刻，测试中可固定或拨动，保证超时与并发断言确定性。
 */
public interface Clock {

    /** 当前时刻（UTC）。 */
    Instant now();
}
