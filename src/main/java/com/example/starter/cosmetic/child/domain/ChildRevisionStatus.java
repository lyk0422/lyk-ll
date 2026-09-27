package com.example.starter.cosmetic.child.domain;

/**
 * 儿童备案修订状态。
 */
public enum ChildRevisionStatus {

    /** 审查不通过（零生效）：缺项一次列全，等待补正。 */
    REJECTED,
    /** 公示中：受理时已冻结报告清单、分龄规则版本与标识文本摘要。 */
    PUBLIC,
    /** 已被新修订取代：补正使旧修订待审批准失效（终态）。 */
    INVALIDATED,
    /** 生效：公示期满或简易变更即时生效。 */
    EFFECTIVE,
    /** 已被生效的新修订替代（终态）。 */
    SUPERSEDED
}
