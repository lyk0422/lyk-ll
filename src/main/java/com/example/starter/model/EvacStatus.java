package com.example.starter.model;

/**
 * 撤人状态：待全员到位 / 已全员确认 / 超时未全员到位。
 */
public enum EvacStatus {
    PENDING,
    CONFIRMED,
    TIMEOUT
}
