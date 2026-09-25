package com.example.starter.atm.domain;

/**
 * ATM（合成数据）。金额单位：分；时间单位：epoch 毫秒（UTC）。
 *
 * @param escortFrozen 押运异常冻结：true 表示该 ATM 已冻结，禁止编制后续计划
 */
public record Atm(
        Long id,
        String atmNo,
        String name,
        long cassetteCapacity,
        long avgDailyWithdrawal,
        boolean escortFrozen,
        long createdAt
) {
}
