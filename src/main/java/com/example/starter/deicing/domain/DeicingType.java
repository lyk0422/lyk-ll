package com.example.starter.deicing.domain;

/**
 * 除冰工艺类型。
 */
public enum DeicingType {

    /**
     * 一步法除冰：单次作业，无防冰液保持时间（HOT）约束。
     */
    ONE_STEP,

    /**
     * 两步法除冰：先除冰后施加防冰液，须在 HOT 内完成起飞。
     */
    TWO_STEP
}
