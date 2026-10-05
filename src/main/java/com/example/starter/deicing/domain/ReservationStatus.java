package com.example.starter.deicing.domain;

/**
 * 除冰液预占状态：ACTIVE 预占中 / SETTLED 已转消耗 / RELEASED 已释放。
 */
public enum ReservationStatus {
    ACTIVE,
    SETTLED,
    RELEASED
}
