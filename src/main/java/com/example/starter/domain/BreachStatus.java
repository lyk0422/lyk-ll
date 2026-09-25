package com.example.starter.domain;

/**
 * 越限事件状态：OPEN=预警中，BRAKED=已下发制动，RESOLVED=吊钩已退出区域。
 */
public enum BreachStatus {
    OPEN,
    BRAKED,
    RESOLVED
}
