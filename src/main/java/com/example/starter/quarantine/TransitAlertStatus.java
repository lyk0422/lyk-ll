package com.example.starter.quarantine;

/**
 * 过境超时预警事件状态：处理闭环 OPEN → RESOLVED。
 */
public enum TransitAlertStatus {

    /** 待处理。 */
    OPEN,
    /** 已办结。 */
    RESOLVED
}
