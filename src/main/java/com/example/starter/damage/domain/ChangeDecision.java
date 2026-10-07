package com.example.starter.damage.domain;

/**
 * 修理变更单复核决定：两名不同复核人均 APPROVE 才切换；任一 REJECT 即驳回候选。
 */
public enum ChangeDecision {
    /** 同意切换 */
    APPROVE,
    /** 驳回候选 */
    REJECT
}
