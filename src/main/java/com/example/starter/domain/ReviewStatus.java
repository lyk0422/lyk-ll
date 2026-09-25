package com.example.starter.domain;

/**
 * 批次审查流程状态。
 */
public enum ReviewStatus {
    /** 资料缺项，已一次性告知补正，等待企业在 5 个工作日内补正 */
    PENDING_CORRECTION,
    /** 资料审查通过（资料目录核对齐全） */
    DOCUMENT_REVIEWED,
    /** 检验核对完成（检验链已逐项复算） */
    INSPECTION_CHECKED,
    /** 综合评定通过 */
    EVALUATION_PASSED,
    /** 综合评定不通过（终态之一） */
    REJECTED,
    /** 公示中（签发结论公示 7 个自然日） */
    PUBLICIZING,
    /** 公示期内异议受理，转复核 */
    OBJECTION_REVIEW,
    /** 公示 7 个自然日期满生效，等待签发归档 */
    PUBLICITY_EFFECTIVE,
    /** 逾期未补正，按撤回处理（终态之一） */
    WITHDRAWN,
    /** 已签发归档（终态） */
    ISSUED
}
