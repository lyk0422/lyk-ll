package com.example.starter.runway;

/**
 * 跑道事件状态机：CLOSED临时关闭 → INSPECTING巡检中 → ASSESSED已评估 →
 * REOPENED已恢复 / 持续关闭（仍为ASSESSED且最近评估不合格） → ARCHIVED归档（终态）。
 */
public enum EventStatus {
    CLOSED,
    INSPECTING,
    ASSESSED,
    REOPENED,
    ARCHIVED
}
