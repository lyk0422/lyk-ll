package com.example.starter.epidemic.domain;

/**
 * 协查单状态：IN_PROGRESS 进行中；CONCLUDED 已办结（结论已回写聚集事件）。
 */
public enum InvestigationStatus {
    /** 进行中：同一聚集事件同一时刻仅允许一张。 */
    IN_PROGRESS,
    /** 已办结：结论已回写聚集事件状态（同一事务）。 */
    CONCLUDED
}
