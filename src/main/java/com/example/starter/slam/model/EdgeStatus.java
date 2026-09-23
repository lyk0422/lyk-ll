package com.example.starter.slam.model;

/**
 * 边入库状态。回环约束校验不通过时仅保存为 REJECTED 审计记录，不进入有效图。
 */
public enum EdgeStatus {
    /**
     * 有效边：参与图版本递增与优化快照。
     */
    VALID,
    /**
     * 被拒绝的回环约束：仅作审计，不参与图构建，不触发图版本递增。
     */
    REJECTED
}
