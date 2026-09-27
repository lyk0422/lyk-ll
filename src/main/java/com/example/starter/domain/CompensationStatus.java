package com.example.starter.domain;

/**
 * 补偿批准状态：ACTIVE 未投料；CONSUMED 已投料（历史不追改）；INVALIDATED 批次系数修订导致未投料批准失效。
 */
public enum CompensationStatus {
    ACTIVE,
    CONSUMED,
    INVALIDATED
}
