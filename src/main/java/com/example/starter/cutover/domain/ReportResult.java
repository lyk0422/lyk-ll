package com.example.starter.cutover.domain;

/**
 * 步骤回报结果：SUCCESS 通过；FAIL 普通失败；HARD_FAIL 硬性检查失败。
 */
public enum ReportResult {

    SUCCESS,
    FAIL,
    HARD_FAIL
}
