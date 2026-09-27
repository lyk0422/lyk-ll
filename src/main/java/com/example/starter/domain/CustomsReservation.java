package com.example.starter.domain;

import java.time.Instant;

/**
 * 包装子批与通关单的预占绑定 PO。每个子批最多绑定一张通关单（uk_reservation_review）；
 * 预占数量在签发原子动作内转已核销，签发前撤回或通关单撤销时仅释放一次。
 */
public class CustomsReservation {

    private Long id;
    private Long customsId;
    private Long reviewId;
    /** 本子批申报数量（正整数，单位：支）。 */
    private Integer declaredQuantity;
    private ReservationStatus status;
    /** 乐观锁版本，从 1 开始，每次成功变更 +1。 */
    private Integer version;
    private Instant reservedAt;
    /** 释放时刻，空值表示未释放；释放只发生一次。 */
    private Instant releasedAt;
    /** 已核销时刻，空值表示未核销。 */
    private Instant writtenOffAt;
    private Instant createdAt;
    private Instant updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getCustomsId() {
        return customsId;
    }

    public void setCustomsId(Long customsId) {
        this.customsId = customsId;
    }

    public Long getReviewId() {
        return reviewId;
    }

    public void setReviewId(Long reviewId) {
        this.reviewId = reviewId;
    }

    public Integer getDeclaredQuantity() {
        return declaredQuantity;
    }

    public void setDeclaredQuantity(Integer declaredQuantity) {
        this.declaredQuantity = declaredQuantity;
    }

    public ReservationStatus getStatus() {
        return status;
    }

    public void setStatus(ReservationStatus status) {
        this.status = status;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public Instant getReservedAt() {
        return reservedAt;
    }

    public void setReservedAt(Instant reservedAt) {
        this.reservedAt = reservedAt;
    }

    public Instant getReleasedAt() {
        return releasedAt;
    }

    public void setReleasedAt(Instant releasedAt) {
        this.releasedAt = releasedAt;
    }

    public Instant getWrittenOffAt() {
        return writtenOffAt;
    }

    public void setWrittenOffAt(Instant writtenOffAt) {
        this.writtenOffAt = writtenOffAt;
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
