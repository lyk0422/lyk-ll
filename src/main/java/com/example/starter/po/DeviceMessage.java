package com.example.starter.po;

import java.time.Instant;

/**
 * 设备消息持久化对象，对应 device_message 表。
 * 待发与 in-flight 消息都保留在本表，ACK 完成后删除并写审计。
 *
 * @param id         自增主键
 * @param deviceId   设备标识
 * @param topic      消息主题
 * @param seq        设备本地单调序号
 * @param priority   业务优先级，数值越大优先级越高
 * @param dedupKey   全局唯一去重键
 * @param payload    合成消息内容
 * @param status     PENDING（待发）/ IN_FLIGHT（已发送待 ACK）
 * @param attempts   已发送次数，超时或重启重试时累加
 * @param enqueuedAt 入队时间
 * @param sentAt     最近一次发送时间，null 表示从未发送
 */
public record DeviceMessage(Long id, String deviceId, String topic, long seq, int priority,
                            String dedupKey, String payload, String status, int attempts,
                            Instant enqueuedAt, Instant sentAt) {
}
