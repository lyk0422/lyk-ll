package com.example.starter.forwarder.model;

/**
 * 设备消息持久化实体，对应 device_message 表。
 */
public class DeviceMessage {

    private Long id;
    /** 消息内部标识 UUID。 */
    private String messageKey;
    private String deviceId;
    private String topic;
    /** 设备本地单调序号（设备内单调递增）。 */
    private long seq;
    /** 业务优先级，数值越大越优先补发。 */
    private int priority;
    /** 全局唯一去重键。 */
    private String dedupKey;
    private String payload;
    private MessageStatus status;
    private long receivedAt;
    private Long inflightDeadline;
    private int dispatchedCount;
    private Long lastDispatchedAt;
    private Long ackedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getMessageKey() {
        return messageKey;
    }

    public void setMessageKey(String messageKey) {
        this.messageKey = messageKey;
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

    public MessageStatus getStatus() {
        return status;
    }

    public void setStatus(MessageStatus status) {
        this.status = status;
    }

    public long getReceivedAt() {
        return receivedAt;
    }

    public void setReceivedAt(long receivedAt) {
        this.receivedAt = receivedAt;
    }

    public Long getInflightDeadline() {
        return inflightDeadline;
    }

    public void setInflightDeadline(Long inflightDeadline) {
        this.inflightDeadline = inflightDeadline;
    }

    public int getDispatchedCount() {
        return dispatchedCount;
    }

    public void setDispatchedCount(int dispatchedCount) {
        this.dispatchedCount = dispatchedCount;
    }

    public Long getLastDispatchedAt() {
        return lastDispatchedAt;
    }

    public void setLastDispatchedAt(Long lastDispatchedAt) {
        this.lastDispatchedAt = lastDispatchedAt;
    }

    public Long getAckedAt() {
        return ackedAt;
    }

    public void setAckedAt(Long ackedAt) {
        this.ackedAt = ackedAt;
    }
}
