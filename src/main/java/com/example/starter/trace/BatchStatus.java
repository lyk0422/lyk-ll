package com.example.starter.trace;

/**
 * 采收批状态：REGISTERED 已登记待检，RELEASED 已放行可进入加工，FROZEN 疑似冻结禁止加工。
 */
public enum BatchStatus {
    REGISTERED,
    RELEASED,
    FROZEN
}
