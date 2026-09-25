package com.example.starter.marpol.domain;

/**
 * 排放记录版本状态。
 */
public enum RecordVersionStatus {
    /** 最新有效版本（查询默认返回）。 */
    ACTIVE,
    /** 已被新版本取代，原版本保留可查。 */
    SUPERSEDED
}
