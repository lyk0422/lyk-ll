package com.example.starter.trace;

/**
 * 来源召回单状态：ACTIVE 活动中（隔离生效），WITHDRAWN 已撤回（仅该召回的关联失效）。
 */
public enum RecallStatus {
    ACTIVE,
    WITHDRAWN
}
