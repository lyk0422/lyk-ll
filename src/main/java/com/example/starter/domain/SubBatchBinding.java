package com.example.starter.domain;

import java.time.Instant;

/**
 * 通关单-包装子批绑定 PO。每个子批（审查流程）只能绑定一张通关单。
 */
public class SubBatchBinding {

    private Long id;
    private Long declarationId;
    private Long reviewId;
    private String batchNo;
    /** 预占申报支数（正整数）。 */
    private int declaredQuantity;
    private BindingStatus status;
    private Instant boundAt;
    private Instant consumedAt;
    private Instant releasedAt;
    /** 释放原因：SUB_BATCH_WITHDRAWN/DECLARATION_REVOKED，空值表示未释放。 */
    private String releaseReason;
    private Instant createdAt;
    private Instant updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getDeclarationId() {
        return declarationId;
    }

    public void setDeclarationId(Long declarationId) {
        this.declarationId = declarationId;
    }

    public Long getReviewId() {
        return reviewId;
    }

    public void setReviewId(Long reviewId) {
        this.reviewId = reviewId;
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

    public Instant getBoundAt() {
        return boundAt;
    }

    public void setBoundAt(Instant boundAt) {
        this.boundAt = boundAt;
    }

    public Instant getConsumedAt() {
        return consumedAt;
    }

    public void setConsumedAt(Instant consumedAt) {
        this.consumedAt = consumedAt;
    }

    public Instant getReleasedAt() {
        return releasedAt;
    }

    public void setReleasedAt(Instant releasedAt) {
        this.releasedAt = releasedAt;
    }

    public String getReleaseReason() {
        return releaseReason;
    }

    public void setReleaseReason(String releaseReason) {
        this.releaseReason = releaseReason;
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
