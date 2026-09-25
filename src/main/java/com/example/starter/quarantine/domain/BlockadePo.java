package com.example.starter.quarantine.domain;

import java.time.Instant;

/**
 * 疫点封锁发布行。
 */
public class BlockadePo {

    private long id;
    private String blockadeId;
    private String farmId;
    private String reason;
    private BlockadeStatus status;
    private Instant effectiveAt;
    private String fingerprint;
    private Instant createdAt;
    private Instant updatedAt;

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getBlockadeId() {
        return blockadeId;
    }

    public void setBlockadeId(String blockadeId) {
        this.blockadeId = blockadeId;
    }

    public String getFarmId() {
        return farmId;
    }

    public void setFarmId(String farmId) {
        this.farmId = farmId;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public BlockadeStatus getStatus() {
        return status;
    }

    public void setStatus(BlockadeStatus status) {
        this.status = status;
    }

    public Instant getEffectiveAt() {
        return effectiveAt;
    }

    public void setEffectiveAt(Instant effectiveAt) {
        this.effectiveAt = effectiveAt;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public void setFingerprint(String fingerprint) {
        this.fingerprint = fingerprint;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
