package com.example.starter.transition.domain;

/**
 * 幂等登记覆盖的业务类型。
 */
public enum BizType {
    GRADE_UPSERT,
    APPLY,
    EXECUTE,
    REGISTER_ROLL,
    INSPECTION,
    APPEAL,
    COMPLETE,
    ARCHIVE
}
