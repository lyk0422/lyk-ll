package com.example.starter.feed.domain;

/**
 * 配方版本状态：SUBMITTED 待审批（合规校验已通过），REJECTED 合规校验拒绝，APPROVED 已批准冻结。
 */
public enum FormulaVersionStatus {
    /** 已提交、合规校验通过、等待批准。 */
    SUBMITTED,
    /** 合规校验拒绝（上限超限、禁忌组合或物种错配）。 */
    REJECTED,
    /** 已批准，版本内容冻结，仅可投产。 */
    APPROVED
}
