package com.example.starter.ledger.po;

import java.time.Instant;

/**
 * 电池流转事件 PO，事件以哈希链串联，可逐条验真。
 */
public class LedgerEventPO {

    /** 主键。 */
    private Long id;
    /** 电池唯一编码。 */
    private String batteryCode;
    /** 链内序号，从 1 递增。 */
    private long seq;
    /** 事件类型，取值见 {@link com.example.starter.ledger.domain.EventType}。 */
    private String eventType;
    /** 上一事件哈希；首事件为 64 个 0。 */
    private String prevHash;
    /** 本事件哈希（SHA-256）。 */
    private String eventHash;
    /** 事件业务字段规范化 JSON。 */
    private String payload;
    /** 产生该事件的幂等请求标识；建档事件为空。 */
    private String requestId;
    /** 事件发生时间（UTC）。 */
    private Instant occurredAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getBatteryCode() {
        return batteryCode;
    }

    public void setBatteryCode(String batteryCode) {
        this.batteryCode = batteryCode;
    }

    public long getSeq() {
        return seq;
    }

    public void setSeq(long seq) {
        this.seq = seq;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public String getPrevHash() {
        return prevHash;
    }

    public void setPrevHash(String prevHash) {
        this.prevHash = prevHash;
    }

    public String getEventHash() {
        return eventHash;
    }

    public void setEventHash(String eventHash) {
        this.eventHash = eventHash;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public void setOccurredAt(Instant occurredAt) {
        this.occurredAt = occurredAt;
    }
}
