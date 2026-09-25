package com.example.starter.domain;

/**
 * 幂等请求类型。
 */
public enum RequestKind {
    /** 告警 */
    ALARM,
    /** 通风令 */
    VENTILATION,
    /** 复测 */
    RETEST,
    /** 归档 */
    ARCHIVE
}
