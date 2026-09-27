package com.example.starter.domain;

/**
 * 采集包状态。
 *
 * <p>{@link #BUFFERED} 乱序缓冲（水位线之后第 1～20 号），不参与自检、互检或见证放行；
 * {@link #CONSUMED} 已按连续完整前缀入账（生成力矩记录）；
 * {@link #LATE} 工序归档后迟到登记，只记证据，不复活卡片。
 */
public enum CollectionPacketStatus {
    /** 乱序缓冲中，等待前序缺号补齐。 */
    BUFFERED,
    /** 已按前缀入账，对应一条力矩记录。 */
    CONSUMED,
    /** 归档后迟到登记，仅证据，不改变工序状态。 */
    LATE
}
