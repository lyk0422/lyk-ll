package com.example.starter.model;

/**
 * 停电事件状态：ACTIVE 停电中；RESOLVED 市电已恢复。
 */
public enum OutageStatus {
    /** 停电中，事件期内重复告警合并到本事件。 */
    ACTIVE,
    /** 市电已恢复。 */
    RESOLVED
}
