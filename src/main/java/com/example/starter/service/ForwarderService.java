package com.example.starter.service;

import com.example.starter.domain.RetentionPolicy;
import com.example.starter.dto.AckResponse;
import com.example.starter.dto.IngestResponse;
import com.example.starter.dto.StatusResponse;

import java.util.List;
import java.util.Map;

/**
 * 边缘离线消息转发核心服务：入队策略、补发调度、ACK 确认与恢复。
 */
public interface ForwarderService {

    /** 配置或更新 Topic 的离线保留策略。 */
    void configureTopic(String topic, RetentionPolicy policy);

    /** 设备写入一条消息，按策略决定接受/丢弃/拒绝/去重。 */
    IngestResponse ingest(String deviceId, String topic, long seq, int priority,
                          String dedupKey, String payload);

    /** 处理一个可重复、乱序到达的 ACK。 */
    AckResponse acknowledge(String dedupKey);

    /** 网络恢复/在线时补发一批待发消息，返回本次发送条数。 */
    int flush();

    /** 切换模拟网络状态。 */
    void setOnline(boolean online);

    StatusResponse status();

    /** 当前所有 Topic 策略（topic -> 规范文本）。 */
    Map<String, String> topicPolicies();
}
