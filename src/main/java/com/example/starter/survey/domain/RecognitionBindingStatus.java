package com.example.starter.survey.domain;

/**
 * 互认绑定状态。
 */
public enum RecognitionBindingStatus {
    /** 有效（每个检验的每个船级项目至多一个）。 */
    ACTIVE,
    /** 已失效（如引用的报告修订被更正替代）。 */
    INVALIDATED
}
