package com.example.starter.domain;

/**
 * 补偿批准状态：
 * FROZEN 已冻结待投料；INVALID 系数修订后未投料批准失效；FED 已投料（历史不追改）。
 */
public enum CompensationStatus {
    FROZEN,
    INVALID,
    FED
}
