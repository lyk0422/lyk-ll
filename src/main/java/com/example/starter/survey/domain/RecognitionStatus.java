package com.example.starter.survey.domain;

/** 互认绑定状态。 */
public enum RecognitionStatus {
    /** 有效。 */
    ACTIVE,
    /** 报告更正导致失效（仅未签发检验；已签发历史绑定不改，另记风险）。 */
    INVALIDATED
}
