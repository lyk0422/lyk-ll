package com.example.starter.domain;

/**
 * 快检判定口径：抑制率 &gt;= 50%（恰 50% 亦判疑似）判 SUSPECTED 疑似超标，否则 PASS 合格。
 */
public enum TestVerdict {
    PASS,
    SUSPECTED;

    /**
     * 按抑制率（%）判定快检结果。
     */
    public static TestVerdict of(java.math.BigDecimal inhibitionRate) {
        return inhibitionRate.compareTo(new java.math.BigDecimal("50")) >= 0 ? SUSPECTED : PASS;
    }
}
