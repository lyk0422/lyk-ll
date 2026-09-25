package com.example.starter.domain;

/**
 * 检测结果。
 */
public enum TestResultCode {

    /** 合格。 */
    PASS,
    /** 不合格。 */
    FAIL;

    public boolean isPass() {
        return this == PASS;
    }
}
