package com.example.starter.forestry.domain;

/**
 * 幂等操作类型。申请、发证、采伐确认与验收提交均以 requestId 幂等。
 */
public enum IdempotentOperation {
    APPLY,
    ISSUE,
    CONFIRM,
    ACCEPTANCE
}
