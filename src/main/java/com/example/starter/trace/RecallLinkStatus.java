package com.example.starter.trace;

/**
 * 召回隔离关联状态：ACTIVE 隔离中，RELEASED 随所属召回撤回而失效（不影响其他召回的关联）。
 */
public enum RecallLinkStatus {
    ACTIVE,
    RELEASED
}
