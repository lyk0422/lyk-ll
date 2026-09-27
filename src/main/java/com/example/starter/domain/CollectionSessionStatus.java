package com.example.starter.domain;

/**
 * 工具采集会话状态机：{@link #OPEN} 采集中 → {@link #CLOSED} 已关闭（终态）。
 * 关闭要求无缺号且全部缓冲包已消费；工序归档前全部采集会话必须关闭。
 */
public enum CollectionSessionStatus {
    /** 采集中，接受上传批次。 */
    OPEN,
    /** 已关闭，终态；归档后迟到包仅登记 LATE 证据。 */
    CLOSED
}
