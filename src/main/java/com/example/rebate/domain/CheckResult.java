package com.example.rebate.domain;

/**
 * 单条关联校验结果。
 */
public enum CheckResult {
    /** 通过。 */
    PASS,
    /** 转疑点（如换汇成本越界、无匹配税率）。 */
    DOUBT
}
