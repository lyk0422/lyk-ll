package com.example.starter.api.dto;

/**
 * 审核决定：APPROVE 时系统仍强制执行替代率/氯硫/许可校验，不通过整单拒绝。
 */
public enum ReviewDecision {
    APPROVE,
    REJECT
}
