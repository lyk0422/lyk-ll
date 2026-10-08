package com.example.starter.trace;

/**
 * 来源召回单状态：ACTIVE 活动中（隔离关联生效），WITHDRAWN 已撤回（仅本召回关联失效）。
 */
public enum RecallStatus {
    ACTIVE,
    WITHDRAWN
}
