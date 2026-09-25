package com.example.starter.domain;

/**
 * 限电指令生命周期状态。
 * RECEIVED 已接收；ALLOCATED 已分摊；DISPATCHED 已下发（执行中）；
 * SUPERSEDED 被新指令作废；SETTLED 日结封存（终态）。
 */
public enum OrderStatus {
    RECEIVED,
    ALLOCATED,
    DISPATCHED,
    SUPERSEDED,
    SETTLED
}
