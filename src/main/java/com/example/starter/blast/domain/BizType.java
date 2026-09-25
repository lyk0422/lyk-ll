package com.example.starter.blast.domain;

/**
 * 幂等业务类型，作为 idempotent_request 主键的一部分，避免不同接口 requestId 撞键。
 */
public enum BizType {
    APPLY,
    FILING,
    REVIEW,
    EVALUATION
}
