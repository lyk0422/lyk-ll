package com.example.starter.signing.domain;

import java.time.Instant;

import com.example.starter.signing.model.EventType;

/**
 * 签署事件链 {@code sign_event} 对应实体。
 */
public class SignEvent {

    /** 自增主键。 */
    private long id;
    /** 所属签署包 ID。 */
    private String packageId;
    /** 包内事件序号，从 1 连续递增。 */
    private int seq;
    /** 事件类型。 */
    private EventType eventType;
    /** 事件发生时的当前修订号。 */
    private int revision;
    /** 事件规范 JSON。 */
    private String detail;
    /** 前一事件哈希，首事件为 64 个 0。 */
    private String previousHash;
    /** 本事件哈希 SHA-256(previous_hash || canonicalEvent)。 */
    private String eventHash;
    /** 事件产生时间（UTC）。 */
    private Instant createdAt;

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getPackageId() {
        return packageId;
    }

    public void setPackageId(String packageId) {
        this.packageId = packageId;
    }

    public int getSeq() {
        return seq;
    }

    public void setSeq(int seq) {
        this.seq = seq;
    }

    public EventType getEventType() {
        return eventType;
    }

    public void setEventType(EventType eventType) {
        this.eventType = eventType;
    }

    public int getRevision() {
        return revision;
    }

    public void setRevision(int revision) {
        this.revision = revision;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }

    public String getPreviousHash() {
        return previousHash;
    }

    public void setPreviousHash(String previousHash) {
        this.previousHash = previousHash;
    }

    public String getEventHash() {
        return eventHash;
    }

    public void setEventHash(String eventHash) {
        this.eventHash = eventHash;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
