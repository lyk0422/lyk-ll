package com.example.starter.model;

/**
 * 油机状态：IDLE 空闲可分配；ASSIGNED 已分配在服务某基站。
 */
public enum GeneratorStatus {
    /** 空闲，可被调度。 */
    IDLE,
    /** 已分配，正在服务某一基站。 */
    ASSIGNED
}
