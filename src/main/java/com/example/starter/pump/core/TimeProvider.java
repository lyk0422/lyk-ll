package com.example.starter.pump.core;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 可控时钟：业务判定“当前时刻”统一取自此接口，测试可固定/推进时间。
 */
public interface TimeProvider {

    ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** 当前 Asia/Shanghai 本地时刻。 */
    LocalDateTime now();

    /** 底层时钟，供需要 Instant 的场景使用。 */
    Clock clock();
}
