package com.example.starter.survey.domain;

/**
 * 外部检查报告逐项结论（合成结论，不来自真实机构）。
 */
public enum ItemResult {
    /** 合格：可作为映射项目的互认候选。 */
    PASS,
    /** 不合格：不得互认。 */
    FAIL,
    /** 重大缺陷：该报告不得当合格项目互认。 */
    MAJOR_DEFECT
}
