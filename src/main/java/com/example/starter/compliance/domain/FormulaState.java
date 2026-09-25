package com.example.starter.compliance.domain;

/**
 * 配方生命周期状态：SUBMITTED 已提交 → REVIEWED 已合规审查 → APPROVED 已批准（版本冻结）。
 */
public enum FormulaState {
    SUBMITTED,
    REVIEWED,
    APPROVED
}
