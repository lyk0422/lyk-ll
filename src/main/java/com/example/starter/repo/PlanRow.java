package com.example.starter.repo;

import com.example.starter.domain.PlanStatus;
import com.example.starter.domain.PlanType;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 加钞计划行（可变工作流实体）。金额单位均为元；时间字段均为 UTC 时刻。
 * countedAmount/deviceAmount 为 NULL 表示尚未清点/设备尚未确认；
 * discrepancyId 为 NULL 表示无挂账；sealedAt 非 NULL 表示已日结封存（终态）。
 * planType 区分常规/节假日临时计划；queueSeq 为同一 ATM 内排队位置，让路（YIELDED）计划保留原位置。
 */
public class PlanRow {

    private Long id;
    private String planNo;
    private long atmId;
    private LocalDate businessDate;
    private PlanType planType = PlanType.REGULAR;
    private long queueSeq;
    private long predictedAmount;
    private long plannedAmount;
    private PlanStatus status;
    private Instant approvedAt;
    private Instant escortDeadline;
    private Instant escortCompletedAt;
    private Long countedAmount;
    private Instant countedAt;
    private Long countCustodianId;
    private Long deviceAmount;
    private Instant deviceConfirmedAt;
    private Instant reconciledAt;
    private Long discrepancyId;
    private Instant sealedAt;
    private Instant createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getPlanNo() {
        return planNo;
    }

    public void setPlanNo(String planNo) {
        this.planNo = planNo;
    }

    public long getAtmId() {
        return atmId;
    }

    public void setAtmId(long atmId) {
        this.atmId = atmId;
    }

    public LocalDate getBusinessDate() {
        return businessDate;
    }

    public void setBusinessDate(LocalDate businessDate) {
        this.businessDate = businessDate;
    }

    public PlanType getPlanType() {
        return planType;
    }

    public void setPlanType(PlanType planType) {
        this.planType = planType;
    }

    public long getQueueSeq() {
        return queueSeq;
    }

    public void setQueueSeq(long queueSeq) {
        this.queueSeq = queueSeq;
    }

    public long getPredictedAmount() {
        return predictedAmount;
    }

    public void setPredictedAmount(long predictedAmount) {
        this.predictedAmount = predictedAmount;
    }

    public long getPlannedAmount() {
        return plannedAmount;
    }

    public void setPlannedAmount(long plannedAmount) {
        this.plannedAmount = plannedAmount;
    }

    public PlanStatus getStatus() {
        return status;
    }

    public void setStatus(PlanStatus status) {
        this.status = status;
    }

    public Instant getApprovedAt() {
        return approvedAt;
    }

    public void setApprovedAt(Instant approvedAt) {
        this.approvedAt = approvedAt;
    }

    public Instant getEscortDeadline() {
        return escortDeadline;
    }

    public void setEscortDeadline(Instant escortDeadline) {
        this.escortDeadline = escortDeadline;
    }

    public Instant getEscortCompletedAt() {
        return escortCompletedAt;
    }

    public void setEscortCompletedAt(Instant escortCompletedAt) {
        this.escortCompletedAt = escortCompletedAt;
    }

    public Long getCountedAmount() {
        return countedAmount;
    }

    public void setCountedAmount(Long countedAmount) {
        this.countedAmount = countedAmount;
    }

    public Instant getCountedAt() {
        return countedAt;
    }

    public void setCountedAt(Instant countedAt) {
        this.countedAt = countedAt;
    }

    public Long getCountCustodianId() {
        return countCustodianId;
    }

    public void setCountCustodianId(Long countCustodianId) {
        this.countCustodianId = countCustodianId;
    }

    public Long getDeviceAmount() {
        return deviceAmount;
    }

    public void setDeviceAmount(Long deviceAmount) {
        this.deviceAmount = deviceAmount;
    }

    public Instant getDeviceConfirmedAt() {
        return deviceConfirmedAt;
    }

    public void setDeviceConfirmedAt(Instant deviceConfirmedAt) {
        this.deviceConfirmedAt = deviceConfirmedAt;
    }

    public Instant getReconciledAt() {
        return reconciledAt;
    }

    public void setReconciledAt(Instant reconciledAt) {
        this.reconciledAt = reconciledAt;
    }

    public Long getDiscrepancyId() {
        return discrepancyId;
    }

    public void setDiscrepancyId(Long discrepancyId) {
        this.discrepancyId = discrepancyId;
    }

    public Instant getSealedAt() {
        return sealedAt;
    }

    public void setSealedAt(Instant sealedAt) {
        this.sealedAt = sealedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
