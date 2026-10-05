package com.example.starter.forestry.domain;

/**
 * 幂等操作类型。申请、蓄积设计值补正、发证、采伐确认与验收提交均以 requestId 幂等。
 */
public enum IdempotentOperation {
    APPLY,
    CORRECT,
    ISSUE,
    CONFIRM,
    ACCEPTANCE
}
