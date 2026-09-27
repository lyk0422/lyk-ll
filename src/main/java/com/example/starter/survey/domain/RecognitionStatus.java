package com.example.starter.survey.domain;

/** 互认绑定状态：ACTIVE 有效 / INVALIDATED 已失效（报告更正触发，仅未签发检验）。 */
public enum RecognitionStatus {
    ACTIVE,
    INVALIDATED
}
