package com.example.starter.time;

import java.time.LocalDate;

/**
 * 业务日期提供者；所有资格与打卡边界均以 UTC 日界判定。
 * 生产实现返回当前 UTC 日期，测试可替换为可控时钟。
 */
public interface TimeProvider {

    /**
     * 返回当前业务日期（UTC 日界）。
     */
    LocalDate today();
}
