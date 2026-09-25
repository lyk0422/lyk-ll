package com.example.starter.deicing.db;

import java.time.Instant;

/**
 * 除冰车位表 deicing_bay 的 PO。
 */
public class DeicingBayPo {

    private long id;
    /** 车位编号，唯一。 */
    private String bayCode;
    /** 车位展示名称。 */
    private String bayName;
    /** 是否启用。 */
    private boolean active;
    private Instant createdAt;

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getBayCode() {
        return bayCode;
    }

    public void setBayCode(String bayCode) {
        this.bayCode = bayCode;
    }

    public String getBayName() {
        return bayName;
    }

    public void setBayName(String bayName) {
        this.bayName = bayName;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
