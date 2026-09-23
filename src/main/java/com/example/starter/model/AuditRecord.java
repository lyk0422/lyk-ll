package com.example.starter.model;

import com.example.starter.enums.AuditEventType;

/**
 * 丢弃、覆盖、驱逐、拒绝等审计记录。
 */
public class AuditRecord {

    private Long id;
    /** 事件类型。 */
    private AuditEventType eventType;
    /** 被处置消息的 dedupKey。 */
    private String messageId;
    /** 设备标识，可为 null。 */
    private String deviceId;
    /** 主题，可为 null。 */
    private String topic;
    /** 设备本地序号，可为 null。 */
    private Long seq;
    /** 业务优先级，可为 null。 */
    private Integer priority;
    /** 处置或拒绝原因的文字说明。 */
    private String reason;
    /** 审计事件时间，Unix 毫秒时间戳（Asia/Shanghai）。 */
    private long createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public AuditEventType getEventType() {
        return eventType;
    }

    public void setEventType(AuditEventType eventType) {
        this.eventType = eventType;
    }

    public String getMessageId() {
        return messageId;
    }

    public void setMessageId(String messageId) {
        this.messageId = messageId;
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

    public Integer getPriority() {
        return priority;
    }

    public void setPriority(Integer priority) {
        this.priority = priority;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }
}
