package com.example.starter.domain;

/**
 * 回路状态。
 */
public enum CircuitStatus {
    /** 正常。 */
    NORMAL,
    /** 事故模式锁定，普通调光请求不得覆盖锁定值。 */
    ACCIDENT
}
