package com.example.starter.domain;

import java.time.Instant;

/**
 * 进口包装子批与通关单绑定 PO。每个子批（审查流程）只能绑定一张通关单；
 * 绑定先预占申报支数，签发转核销，签发前撤回或通关单撤销释放预占（只释放一次）。
 */
public class SubbatchBinding {

    private Long id;
    private Long reviewId;
    private Long customsDeclarationId;
    private String batchNo;
    /** 本子批申报预占支数（正整数，单位：支）。 */
    private int declaredQuantity;
    private BindingStatus status;
    /** 乐观版本号：绑定为 1，撤回释放成功后加 1。 */
    private int version;
    private Instant reservedAt;
    private Instant writtenOffAt;
    private Instant releasedAt;
    private Instant createdAt;
    private Instant updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getReviewId() {
        return reviewId;
    }

    public void setReviewId(Long reviewId) {
        this.reviewId = reviewId;
    }

    public Long getCustomsDeclarationId() {
        return customsDeclarationId;
    }

    public void setCustomsDeclarationId(Long customsDeclarationId) {
        this.customsDeclarationId = customsDeclarationId;
    }

    public String getBatchNo() {
        return batchNo;
    }

    public void setBatchNo(String batchNo) {
        this.batchNo = batchNo;
    }

    public int getDeclaredQuantity() {
        return declaredQuantity;
    }

    public void setDeclaredQuantity(int declaredQuantity) {
        this.declaredQuantity = declaredQuantity;
    }

    public BindingStatus getStatus() {
        return status;
    }

    public void setStatus(BindingStatus status) {
        this.status = status;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public Instant getReservedAt() {
        return reservedAt;
    }

    public void setReservedAt(Instant reservedAt) {
        this.reservedAt = reservedAt;
    }

    public Instant getWrittenOffAt() {
        return writtenOffAt;
    }

    public void setWrittenOffAt(Instant writtenOffAt) {
        this.writtenOffAt = writtenOffAt;
    }

    public Instant getReleasedAt() {
        return releasedAt;
    }

    public void setReleasedAt(Instant releasedAt) {
        this.releasedAt = releasedAt;
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
