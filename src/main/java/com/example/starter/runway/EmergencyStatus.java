package com.example.starter.runway;

/**
 * 紧急关闭事件状态：ACTIVE 进行中（占用跑道）→ ENDED 已结束（终态）。
 */
public enum EmergencyStatus {
    ACTIVE,
    ENDED
}
