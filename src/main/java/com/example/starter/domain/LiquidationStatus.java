package com.example.starter.domain;

/**
 * 强平指令状态：PENDING 待发（可撤销）；DONE 已执行（终态）；CANCELLED 已撤销（终态）。
 */
public enum LiquidationStatus {
    PENDING,
    DONE,
    CANCELLED
}
