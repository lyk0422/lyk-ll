package com.example.starter.time;

import java.time.Instant;

/**
 * 业务时钟。默认走系统 UTC 时间；测试可替换为可控时钟，保证时间边界用例确定性。
 */
public interface BusinessClock {

    /** 当前业务时刻（UTC）。 */
    Instant now();
}
