package com.example.starter.domain;

/**
 * 检测项：初检与复检为两项独立检测，结果可乱序回传。
 */
public enum TestType {
    /** 初检。 */
    INITIAL,
    /** 复检。 */
    RECHECK
}
