package com.example.starter.domain;

/**
 * 采集原始包状态。
 *
 * <p>{@link #BUFFERED} 乱序缓冲中（水位线之后第 1～20 号窗口内，尚未形成连续前缀，
 * 不参与自检互检与见证放行）；{@link #CONSUMED} 已按序入财力矩链；
 * {@link #LATE} 工序归档后到达，只记迟到事件，不复活卡片。
 */
public enum AcquisitionPacketStatus {
    /** 乱序缓冲中，等待缺号补齐后按序入账。 */
    BUFFERED,
    /** 已随连续完整前缀入财力矩链。 */
    CONSUMED,
    /** 归档后迟到，仅登记迟到事件。 */
    LATE
}
