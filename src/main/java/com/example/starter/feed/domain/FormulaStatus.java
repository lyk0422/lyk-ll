package com.example.starter.feed.domain;

/**
 * 配方流转状态：提交(SUBMITTED)→批准(APPROVED)→投产(IN_PRODUCTION)；合规校验拒绝为 REJECTED。
 */
public enum FormulaStatus {
    /** 已提交，待合规校验通过后批准。 */
    SUBMITTED,
    /** 合规校验拒绝（超上限/禁忌/动物种类错配等）。 */
    REJECTED,
    /** 已批准，版本内容冻结。 */
    APPROVED,
    /** 已投产；休药义务按投产时刻的冻结快照计算。 */
    IN_PRODUCTION
}
