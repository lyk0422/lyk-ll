package com.example.starter.bag.model;

/**
 * 逻辑分段状态。
 */
public enum SegmentStatus {

    /** 可继续追加消息 */
    OPEN,
    /** 已达到容量封段，只读 */
    SEALED,
    /** 写入中断或索引损坏，等待恢复 */
    DAMAGED
}
