package com.example.starter.survey.domain;

/**
 * 互认规则版本状态。
 */
public enum RecognitionRuleStatus {
    /** 可被新互认申请引用。 */
    ACTIVE,
    /** 停用：不可被新申请引用；已冻结引用该版本的历史绑定不受影响。 */
    FROZEN
}
