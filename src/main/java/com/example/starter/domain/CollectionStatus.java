package com.example.starter.domain;

/**
 * 采集会话状态机。
 *
 * <p>{@link #OPEN} 采集中（可提交乱序包、水位线持续推进）；
 * {@link #CLOSED} 已关闭：无缺号且全部缓冲已消费，终态不可再提交入账包。
 */
public enum CollectionStatus {
    /** 采集中。 */
    OPEN,
    /** 已关闭（无缺号、缓冲全部消费），终态。 */
    CLOSED
}
