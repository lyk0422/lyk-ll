package com.example.starter.repo;

import com.example.starter.domain.DiscrepancyStatus;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 长短款挂账行（可变）。amount=实际清点数-计划数，正为长款、负为短款；
 * dueDate=occurredDate+3 个自然日（UTC 日界），恰第 3 日提交销账仍受理。
 */
public class DiscrepancyRow {

    private Long id;
    private long planId;
    private long amount;
    private DiscrepancyStatus status;
    private long responsibleCustodianId;
    private LocalDate occurredDate;
    private LocalDate dueDate;
    private Instant settledAt;
    private Instant createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public long getPlanId() {
        return planId;
    }

    public void setPlanId(long planId) {
        this.planId = planId;
    }

    public long getAmount() {
        return amount;
    }

    public void setAmount(long amount) {
        this.amount = amount;
    }

    public DiscrepancyStatus getStatus() {
        return status;
    }

    public void setStatus(DiscrepancyStatus status) {
        this.status = status;
    }

    public long getResponsibleCustodianId() {
        return responsibleCustodianId;
    }

    public void setResponsibleCustodianId(long responsibleCustodianId) {
        this.responsibleCustodianId = responsibleCustodianId;
    }

    public LocalDate getOccurredDate() {
        return occurredDate;
    }

    public void setOccurredDate(LocalDate occurredDate) {
        this.occurredDate = occurredDate;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    public void setDueDate(LocalDate dueDate) {
        this.dueDate = dueDate;
    }

    public Instant getSettledAt() {
        return settledAt;
    }

    public void setSettledAt(Instant settledAt) {
        this.settledAt = settledAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
