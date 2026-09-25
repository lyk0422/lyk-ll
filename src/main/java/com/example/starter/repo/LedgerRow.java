package com.example.starter.repo;

/**
 * diver_time_ledger 行记录（只读复算用）：时刻为 UTC 纪元秒，durationSeconds 为秒。
 */
public record LedgerRow(String diverId, long startedAt, long endedAt, long durationSeconds) {
}
