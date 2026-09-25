package com.example.starter.slaughter.domain;

/**
 * 宰前检疫结论。
 */
public enum PreQuarantineResult {

    /** 检疫合格，可在 6 小时（UTC）有效期内屠宰。 */
    PASS,
    /** 检疫不合格，禁止屠宰。 */
    FAIL
}
