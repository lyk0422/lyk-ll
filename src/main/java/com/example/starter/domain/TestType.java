package com.example.starter.domain;

/**
 * 检测类型：初检与复检是两项互相独立的检测，结果可乱序回传。
 */
public enum TestType {

    /** 初检。 */
    INITIAL,
    /** 复检。 */
    REPEAT
}
