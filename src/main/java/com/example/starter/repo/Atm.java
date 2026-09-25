package com.example.starter.repo;

import java.time.Instant;

/**
 * ATM 设备行。金额单位为元；frozen=1 表示因押运异常被冻结，不再受理新的加钞计划。
 */
public record Atm(
        Long id,
        String code,
        String name,
        long capacity,
        long dailyAvgWithdrawal,
        boolean frozen,
        Instant createdAt
) {
}
