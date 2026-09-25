package com.example.starter.domain;

/**
 * 列车作业状态：ACCEPTED 接入；CHECKED 编组核对通过；DEPARTURE_CONFIRMED 发车确认；CLOSED 销记终态。
 */
public enum JobStatus {
    ACCEPTED,
    CHECKED,
    DEPARTURE_CONFIRMED,
    CLOSED
}
