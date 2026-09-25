package com.example.starter.quarantine.domain;

import java.time.Instant;

/**
 * 检疫申报单据行。
 */
public class QuarantineRequestPo {

    private String requestId;
    private String originFarm;
    private String destination;
    private AnimalType animalType;
    private String earTagRange;
    private int quantity;
    private RequestStatus status;
    private String fingerprint;
    private String rejectReason;
    private Instant createdAt;
    private Instant updatedAt;

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public String getOriginFarm() {
        return originFarm;
    }

    public void setOriginFarm(String originFarm) {
        this.originFarm = originFarm;
    }

    public String getDestination() {
        return destination;
    }

    public void setDestination(String destination) {
        this.destination = destination;
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

    public RequestStatus getStatus() {
        return status;
    }

    public void setStatus(RequestStatus status) {
        this.status = status;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public void setFingerprint(String fingerprint) {
        this.fingerprint = fingerprint;
    }

    public String getRejectReason() {
        return rejectReason;
    }

    public void setRejectReason(String rejectReason) {
        this.rejectReason = rejectReason;
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
