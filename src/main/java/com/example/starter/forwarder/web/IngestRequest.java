package com.example.starter.forwarder.web;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 设备消息写入请求（合成设备调用入口）。
 */
public class IngestRequest {

    @NotBlank(message = "deviceId 不能为空")
    private String deviceId;

    @NotBlank(message = "topic 不能为空")
    private String topic;

    @NotNull(message = "seq 不能为空")
    @Min(value = 0, message = "seq 不能为负")
    private Long seq;

    @NotNull(message = "priority 不能为空")
    private Integer priority;

    @NotBlank(message = "dedupKey 不能为空")
    private String dedupKey;

    private String payload;

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

    public Integer getPriority() {
        return priority;
    }

    public void setPriority(Integer priority) {
        this.priority = priority;
    }

    public String getDedupKey() {
        return dedupKey;
    }

    public void setDedupKey(String dedupKey) {
        this.dedupKey = dedupKey;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }
}
