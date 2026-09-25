package com.example.starter.domain;

/**
 * 批签发审查流程状态机：申报→资料审查→检验核对→综合评定→公示→签发→归档。
 */
public enum ProcessStatus {

    /** 待补正：资料缺项已一次性告知，须在5个工作日内补正 */
    PENDING_CORRECTION,
    /** 待综合评定：资料齐全、检验核对完成 */
    UNDER_REVIEW,
    /** 评定不合格：关键检验项目未全项合格 */
    EVALUATED_FAIL,
    /** 公示中：签发结论公示7个自然日（UTC日界） */
    IN_PUBLICITY,
    /** 异议复核：公示期内受理异议，转入复核，流程终止 */
    OBJECTION_REVIEW,
    /** 已签发：公示期满生效 */
    ISSUED,
    /** 签发归档：终态，重复归档幂等成功，迟到资料只记事件 */
    ARCHIVED,
    /** 逾期撤回：补正超过5个工作日时限 */
    WITHDRAWN
}
