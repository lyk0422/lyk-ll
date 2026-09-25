package com.example.starter.weight.domain;

/**
 * 处罚决定执行状态。
 */
public enum DecisionStatus {
    /**
     * 决定已送达并生效；复检期间亦不停止执行。
     */
    ACTIVE,
    /**
     * 经复检推翻，原决定已在同一事务内撤销。
     */
    REVOKED
}
