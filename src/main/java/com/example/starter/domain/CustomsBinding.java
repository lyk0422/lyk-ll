package com.example.starter.domain;

import java.time.Instant;

/**
 * 包装子批绑定 PO。一个子批（审查流程）至多一行，只能绑定一张通关单。
 */
public class CustomsBinding {

    private Long id;
    private Long declarationId;
    private Long reviewId;
    /** 本子批申报预占支数（正整数）。 */
    private int declaredDoses;
    private BindingStatus status;
    /** 释放原因，空值表示未释放。 */
    private ReleaseReason releaseReason;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant consumedAt;
    private Instant releasedAt;

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

    public int getDeclaredDoses() {
        return declaredDoses;
    }

    public void setDeclaredDoses(int declaredDoses) {
        this.declaredDoses = declaredDoses;
    }

    public BindingStatus getStatus() {
        return status;
    }

    public void setStatus(BindingStatus status) {
        this.status = status;
    }

    public ReleaseReason getReleaseReason() {
        return releaseReason;
    }

    public void setReleaseReason(ReleaseReason releaseReason) {
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
}
