package com.example.starter.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 设备上报消息请求。
 */
public class MessageRequest {

    /** 全局唯一去重键 dedupKey。 */
    @NotBlank
    private String dedupKey;

    /** 边缘设备标识。 */
    @NotBlank
    private String deviceId;

    /** 消息主题。 */
    @NotBlank
    private String topic;

    /** 设备本地单调递增序号。 */
    @NotNull
    @PositiveOrZero
    private Long seq;

    /** 业务优先级，数值越大优先级越高。 */
    private int priority;

    /** 消息业务负载（合成文本）。 */
    private String payload;

    public String getDedupKey() {
        return dedupKey;
    }

    public void setDedupKey(String dedupKey) {
        this.dedupKey = dedupKey;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }

    public String getTopic() {
        return topic;
    }

    public void setTopic(String topic) {
        this.topic = topic;
    }

    public Long getSeq() {
        return seq;
    }

    public void setSeq(Long seq) {
        this.seq = seq;
    }

    public int getPriority() {
        return priority;
    }

    public void setPriority(int priority) {
        this.priority = priority;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }
}
