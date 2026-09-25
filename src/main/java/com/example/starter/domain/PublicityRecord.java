package com.example.starter.domain;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 公示记录 PO（公示链，可复算）。
 */
public class PublicityRecord {

    private Long id;
    private Long reviewId;
    private LocalDate startUtcDate;
    private LocalDate endUtcDate;
    private PublicityStatus status;
    private Instant objectionAt;
    private Instant effectiveAt;

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

    public LocalDate getStartUtcDate() {
        return startUtcDate;
    }

    public void setStartUtcDate(LocalDate startUtcDate) {
        this.startUtcDate = startUtcDate;
    }

    public LocalDate getEndUtcDate() {
        return endUtcDate;
    }

    public void setEndUtcDate(LocalDate endUtcDate) {
        this.endUtcDate = endUtcDate;
    }

    public PublicityStatus getStatus() {
        return status;
    }

    public void setStatus(PublicityStatus status) {
        this.status = status;
    }

    public Instant getObjectionAt() {
        return objectionAt;
    }

    public void setObjectionAt(Instant objectionAt) {
        this.objectionAt = objectionAt;
    }

    public Instant getEffectiveAt() {
        return effectiveAt;
    }

    public void setEffectiveAt(Instant effectiveAt) {
        this.effectiveAt = effectiveAt;
    }
}
