package com.example.starter.trial.domain;

/**
 * 受试者状态。
 */
public enum SubjectStatus {
    /** 在组（盲态） */
    ENROLLED,
    /** 已揭盲（终态，不可回退） */
    UNBLINDED
}
