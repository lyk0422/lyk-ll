package com.example.starter.domain;

/**
 * 列车作业状态: RECEIVED=作业中, DEPARTURE_CONFIRMED=已发车确认, CLOSED=已销记(终态)。
 */
public enum OperationStatus {
    RECEIVED,
    DEPARTURE_CONFIRMED,
    CLOSED
}
