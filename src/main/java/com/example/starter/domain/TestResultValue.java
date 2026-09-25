package com.example.starter.domain;

/**
 * 检测结果值：合格或不合格。
 */
public enum TestResultValue {
    /** 合格。 */
    PASS,
    /** 不合格，直接导致报废终态。 */
    FAIL
}
