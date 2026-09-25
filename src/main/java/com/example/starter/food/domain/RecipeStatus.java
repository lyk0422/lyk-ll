package com.example.starter.food.domain;

/**
 * 配方生命周期状态：提交 -> 合规审查 -> 批准 -> 量产备案。
 */
public enum RecipeStatus {
    /** 已提交，尚未审查 */
    SUBMITTED,
    /** 合规审查通过（任一违例则整单进入 REJECTED） */
    COMPLIANT,
    /** 合规审查拒绝（整单拒绝，不产生部分记录） */
    REJECTED,
    /** 已批准，版本快照冻结 */
    APPROVED
}
