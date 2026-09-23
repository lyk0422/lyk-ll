package com.example.starter.model;

import com.example.starter.enums.MessageStatus;

/**
 * 设备消息，覆盖待发、in-flight、已确认全生命周期。
 */
public class DeviceMessage {

    private Long id;
    /** 全局唯一去重键 dedupKey。 */
    private String messageId;
    /** 边缘设备标识。 */
    private String deviceId;
    /** 消息主题。 */
    private String topic;
    /** 设备本地单调递增序号（同一设备内单调）。 */
    private long seq;
    /** 业务优先级，数值越大优先级越高。 */
    private int priority;
    /** 消息业务负载（合成文本，可为 null）。 */
    private String payload;
    /** 生命周期状态。 */
    private MessageStatus status;
    /** 已尝试投递次数。 */
    private int attempts;
    /** 入队（写入本地）时间，Unix 毫秒时间戳。 */
    private long enqueuedAt;
    /** 最近一次进入 in-flight 的时间，null 表示当前不在投递中。 */
    private Long inflightAt;
    /** 最近一次成功投递给上游适配器的时间，null 表示尚未投递成功。 */
    private Long forwardedAt;
    /** 收到明确 ACK 的时间，null 表示未确认。 */
    private Long ackedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
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

    public long getSeq() {
        return seq;
    }

    public void setSeq(long seq) {
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

    public MessageStatus getStatus() {
        return status;
    }

    public void setStatus(MessageStatus status) {
        this.status = status;
    }

    public int getAttempts() {
        return attempts;
    }

    public void setAttempts(int attempts) {
        this.attempts = attempts;
    }

    public long getEnqueuedAt() {
        return enqueuedAt;
    }

    public void setEnqueuedAt(long enqueuedAt) {
        this.enqueuedAt = enqueuedAt;
    }

    public Long getInflightAt() {
        return inflightAt;
    }

    public void setInflightAt(Long inflightAt) {
        this.inflightAt = inflightAt;
    }

    public Long getForwardedAt() {
        return forwardedAt;
    }

    public void setForwardedAt(Long forwardedAt) {
        this.forwardedAt = forwardedAt;
    }

    public Long getAckedAt() {
        return ackedAt;
    }

    public void setAckedAt(Long ackedAt) {
        this.ackedAt = ackedAt;
    }
}
