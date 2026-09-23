package com.example.starter.calibration.domain;

/**
 * 灰度发布单状态：ACTIVE 灰度中、PROMOTED 已全量、ROLLED_BACK 已回滚。
 */
public enum ReleaseStatus {
    ACTIVE,
    PROMOTED,
    ROLLED_BACK
}
