package com.example.starter.sensor.domain;

/**
 * 传感器消息摄取结果状态。
 *
 * <ul>
 *     <li>{@link #ACCEPTED}：进入水位线容忍范围内，可参与候选融合帧；</li>
 *     <li>{@link #LATE}：晚于水位线到达，已记录丢弃原因，不参与任何融合帧；</li>
 *     <li>{@link #DUPLICATE}：消息 ID 重复，已记录丢弃原因，不改变既有事件。</li>
 * </ul>
 */
public enum IngestStatus {
    ACCEPTED,
    LATE,
    DUPLICATE
}
