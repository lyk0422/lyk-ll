package com.example.starter.rosbridge.protocol;

/**
 * Service 调用状态。
 * PENDING 进行中；COMPLETED 成功；TIMEOUT 超时；FAILED 适配器失败。
 */
public enum CallStatus {
    PENDING,
    COMPLETED,
    TIMEOUT,
    FAILED
}
