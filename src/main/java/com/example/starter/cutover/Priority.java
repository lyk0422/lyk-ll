package com.example.starter.cutover;

/**
 * 割接优先级。同窗口冲突时高优先级（HIGH）优先，低优先级申请被拒绝。
 */
public enum Priority {
    HIGH,
    MEDIUM,
    LOW
}
