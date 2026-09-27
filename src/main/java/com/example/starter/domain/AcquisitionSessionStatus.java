package com.example.starter.domain;

/**
 * 工具采集会话状态机。
 *
 * <p>{@link #OPEN} 采集中，可上传批次；{@link #CLOSED} 已关闭（终态），
 * 关闭要求无缺号且全部缓冲包已消费；工序归档前全部采集会话须关闭。
 */
public enum AcquisitionSessionStatus {
    /** 采集中，受理批次上传。 */
    OPEN,
    /** 已关闭（终态），不再受理普通批次；归档后迟到包仍只记迟到。 */
    CLOSED
}
