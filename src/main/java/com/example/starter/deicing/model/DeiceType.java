package com.example.starter.deicing.model;

/**
 * 除冰类型。
 */
public enum DeiceType {

    /** 一步法：单步作业，无防冰液保持时间（HOT）约束。 */
    ONE_STEP,

    /** 两步法：两步作业，必须在 HOT 内完成起飞。 */
    TWO_STEP
}
