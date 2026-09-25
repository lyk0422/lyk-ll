package com.example.starter.domain;

/**
 * 调节任务状态。
 *
 * <ul>
 *   <li>{@link #EXECUTING} 执行中：初调节后持续监测、判定、再调节的活跃阶段；</li>
 *   <li>{@link #ARCHIVED} 已归档：效果评估归档后的终态，不可再调节或评估。</li>
 * </ul>
 */
public enum TaskStatus {
    EXECUTING,
    ARCHIVED
}
