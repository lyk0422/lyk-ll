package com.example.starter.quarantine.domain;

import java.time.Instant;

/**
 * 检疫合格证行。同一养殖场同一耳标段至多一张有效证（active_slot 唯一）。
 */
public class CertificatePo {

    private long id;
    private String certificateNo;
    private String requestId;
    private String farmId;
    private AnimalType animalType;
    private String earTagRange;
    private int quantity;
    private Instant issuedAt;
    private Instant expiresAt;
    private CertificateStatus status;
    private boolean quarantineFlag;
    private String activeSlot;
    private String voidReason;
    private Instant createdAt;
    private Instant updatedAt;

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getCertificateNo() {
        return certificateNo;
    }

    public void setCertificateNo(String certificateNo) {
        this.certificateNo = certificateNo;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public String getFarmId() {
        return farmId;
    }

    public void setFarmId(String farmId) {
        this.farmId = farmId;
    }

    public AnimalType getAnimalType() {
        return animalType;
    }

    public void setAnimalType(AnimalType animalType) {
        this.animalType = animalType;
    }

    public String getEarTagRange() {
        return earTagRange;
    }

    public void setEarTagRange(String earTagRange) {
        this.earTagRange = earTagRange;
    }

    public int getQuantity() {
        return quantity;
    }

    public void setQuantity(int quantity) {
        this.quantity = quantity;
    }

    public Instant getIssuedAt() {
        return issuedAt;
    }

    public void setIssuedAt(Instant issuedAt) {
        this.issuedAt = issuedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public CertificateStatus getStatus() {
        return status;
    }

    public void setStatus(CertificateStatus status) {
        this.status = status;
    }

    public boolean isQuarantineFlag() {
        return quarantineFlag;
    }

    public void setQuarantineFlag(boolean quarantineFlag) {
        this.quarantineFlag = quarantineFlag;
    }

    public String getActiveSlot() {
        return activeSlot;
    }

    public void setActiveSlot(String activeSlot) {
        this.activeSlot = activeSlot;
    }

    public String getVoidReason() {
        return voidReason;
    }

    public void setVoidReason(String voidReason) {
        this.voidReason = voidReason;
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
