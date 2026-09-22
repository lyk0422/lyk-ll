package com.example.starter.sensor.domain;

/**
 * 融合帧状态。
 */
public enum FrameStatus {
    /** 已提交：不可变，迟到/重复/乱序消息不得修改。 */
    COMMITTED,
    /** 构建被拒绝（缺帧、超窗、无法插值等），仅记录原因。 */
    REJECTED
}
