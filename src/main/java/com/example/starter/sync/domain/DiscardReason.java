package com.example.starter.sync.domain;

/**
 * 消息丢弃原因码（稳定枚举，用于定位）。
 */
public enum DiscardReason {
    /** 同一传感器内消息ID重复，重放幂等丢弃。 */
    DUPLICATE_MESSAGE,
    /** 校正时戳低于水位线减去迟到容忍，过晚到达。 */
    LATE_MESSAGE,
    /** 校正时戳已落入某个已提交融合帧的窗口，不允许修改历史帧。 */
    FRAME_ALREADY_COMMITTED
}
