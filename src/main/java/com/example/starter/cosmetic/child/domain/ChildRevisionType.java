package com.example.starter.cosmetic.child.domain;

/**
 * 儿童备案修订类型。
 */
public enum ChildRevisionType {

    /** 首次提交。 */
    INITIAL,
    /** 材料补正：生成新修订并使旧修订待审批准失效。 */
    CORRECTION,
    /** 实质变更：儿童属性、年龄或标识适用范围等变化，重新审查并公示。 */
    SUBSTANTIVE,
    /** 简易变更：仅包装文字类调整，即时生效。 */
    SIMPLE
}
