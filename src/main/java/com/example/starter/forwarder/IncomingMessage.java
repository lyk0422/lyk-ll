package com.example.starter.forwarder;

/**
 * 设备上报的一条消息。
 *
 * @param deviceId 设备 ID
 * @param topic ROS 2 Topic
 * @param deviceSeq 设备本地单调序号
 * @param priority 业务优先级，数值越大越优先补发
 * @param dedupKey 全局唯一去重键
 * @param payload 消息体（合成数据）
 */
public record IncomingMessage(
        String deviceId,
        String topic,
        long deviceSeq,
        int priority,
        String dedupKey,
        String payload) {
}
