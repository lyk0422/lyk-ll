package com.example.starter.pension.domain;

/**
 * 模拟托管行出款结果。
 */
public enum AttemptResult {
    /** 出款成功。 */
    SUCCESS,
    /** 出款失败，可换用新的 requestId 重试。 */
    FAILED
}
