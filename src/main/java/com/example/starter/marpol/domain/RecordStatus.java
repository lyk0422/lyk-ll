package com.example.starter.marpol.domain;

/**
 * 排放记录版本状态。
 */
public enum RecordStatus {
    /** 最新有效版本，查询默认返回。 */
    ACTIVE,
    /** 被修正取代的历史版本，物理保留、仍可按版本查询。 */
    SUPERSEDED
}
