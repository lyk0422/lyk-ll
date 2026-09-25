package com.example.starter.feed.domain;

/**
 * 养殖批次状态。出栏(SLAUGHTERED)为终态。
 */
public enum BatchStatus {
    /** 饲养中（含休药期跟踪阶段）。 */
    FEEDING,
    /** 已出栏，终态；迟到的含药饲喂只记事件并告警。 */
    SLAUGHTERED
}
