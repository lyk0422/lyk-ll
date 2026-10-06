package com.example.starter.weight.domain;

/**
 * 非现场称重接入状态。
 */
public enum OffsiteStatus {
    /**
     * 证据链完整，已自动生成处罚决定。
     */
    AUTO_DECIDED,
    /**
     * 证据链缺项（缺影像/缺设备状态/校准有效期外），转人工待补，不自动生成处罚决定。
     */
    MANUAL_PENDING,
    /**
     * 结案后迟到数据，只记迟到事件，不改已结案件。
     */
    LATE_EVENT
}
