package com.example.starter.domain;

/**
 * 采集原始包状态。
 *
 * <p>{@link #ADMITTED} 已随连续前缀入账（产生力矩记录，参与自检/互检/见证放行）；
 * {@link #BUFFERED} 处于水位线之后第 1～20 号乱序窗口内，仅缓冲、不入账、不参与放行；
 * {@link #LATE} 工序卡归档后才到达，只记迟到事件，不复活卡片。
 */
public enum PacketState {
    /** 已入连续前缀，力矩记录已写入。 */
    ADMITTED,
    /** 乱序缓冲中（缺号未齐），无力矩记录。 */
    BUFFERED,
    /** 归档后迟到，仅登记。 */
    LATE
}
