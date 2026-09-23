package com.example.starter.calibration.domain;

/**
 * 参数版本审批/发布状态：
 * DRAFT 草稿、APPROVED 已批准、CANARY 灰度中、ACTIVE 已生效、
 * ROLLED_BACK 已回滚、SUPERSEDED 被正式新版本替代。
 */
public enum ParamStatus {
    DRAFT,
    APPROVED,
    CANARY,
    ACTIVE,
    ROLLED_BACK,
    SUPERSEDED
}
