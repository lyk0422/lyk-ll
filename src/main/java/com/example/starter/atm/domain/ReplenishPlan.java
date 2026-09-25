package com.example.starter.atm.domain;

/**
 * 加钞计划聚合根。金额单位：分；时间单位：epoch 毫秒（UTC）。
 * 状态流转：PREPARED -> APPROVED -> IN_TRANSIT -> ESCORT_DONE -> COUNTED
 *          -> DEVICE_CONFIRMED -> RECONCILED -> SEALED；
 * 三方不一致转 OVER_SHORT -> OVER_SHORT_SETTLED -> SEALED；
 * 押运恰满 4 小时未完成打卡转 ESCORT_ABNORMAL（冻结该 ATM 后续计划）。
 */
public class ReplenishPlan {

    private Long id;
    private String planNo;
    private String requestId;
    private long atmId;
    private long boxId;
    private String planUtcDate;
    private long predictedAmount;
    private long plannedAmount;
    private PlanStatus status;
    private Long approvedAt;
    private Long escortDeadline;
    private Long countedAmount;
    private Long countedAt;
    private Long countGuard1Id;
    private Long countGuard2Id;
    private Long deviceAmount;
    private Long deviceConfirmedAt;
    private Long reconciledAt;
    private Long overShortId;
    private Long sealedAt;
    /** 执行中单标记：true 参与同 ATM 同日唯一受理；false 表示已终态（封存/押运异常），放行同日新单。 */
    private Boolean activeFlag;
    private int version;
    private long createdAt;
    private long updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getPlanNo() { return planNo; }
    public void setPlanNo(String planNo) { this.planNo = planNo; }

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }

    public long getAtmId() { return atmId; }
    public void setAtmId(long atmId) { this.atmId = atmId; }

    public long getBoxId() { return boxId; }
    public void setBoxId(long boxId) { this.boxId = boxId; }

    public String getPlanUtcDate() { return planUtcDate; }
    public void setPlanUtcDate(String planUtcDate) { this.planUtcDate = planUtcDate; }

    public long getPredictedAmount() { return predictedAmount; }
    public void setPredictedAmount(long predictedAmount) { this.predictedAmount = predictedAmount; }

    public long getPlannedAmount() { return plannedAmount; }
    public void setPlannedAmount(long plannedAmount) { this.plannedAmount = plannedAmount; }

    public PlanStatus getStatus() { return status; }
    public void setStatus(PlanStatus status) { this.status = status; }

    public Long getApprovedAt() { return approvedAt; }
    public void setApprovedAt(Long approvedAt) { this.approvedAt = approvedAt; }

    public Long getEscortDeadline() { return escortDeadline; }
    public void setEscortDeadline(Long escortDeadline) { this.escortDeadline = escortDeadline; }

    public Long getCountedAmount() { return countedAmount; }
    public void setCountedAmount(Long countedAmount) { this.countedAmount = countedAmount; }

    public Long getCountedAt() { return countedAt; }
    public void setCountedAt(Long countedAt) { this.countedAt = countedAt; }

    public Long getCountGuard1Id() { return countGuard1Id; }
    public void setCountGuard1Id(Long countGuard1Id) { this.countGuard1Id = countGuard1Id; }

    public Long getCountGuard2Id() { return countGuard2Id; }
    public void setCountGuard2Id(Long countGuard2Id) { this.countGuard2Id = countGuard2Id; }

    public Long getDeviceAmount() { return deviceAmount; }
    public void setDeviceAmount(Long deviceAmount) { this.deviceAmount = deviceAmount; }

    public Long getDeviceConfirmedAt() { return deviceConfirmedAt; }
    public void setDeviceConfirmedAt(Long deviceConfirmedAt) { this.deviceConfirmedAt = deviceConfirmedAt; }

    public Long getReconciledAt() { return reconciledAt; }
    public void setReconciledAt(Long reconciledAt) { this.reconciledAt = reconciledAt; }

    public Long getOverShortId() { return overShortId; }
    public void setOverShortId(Long overShortId) { this.overShortId = overShortId; }

    public Long getSealedAt() { return sealedAt; }
    public void setSealedAt(Long sealedAt) { this.sealedAt = sealedAt; }

    public Boolean getActiveFlag() { return activeFlag; }
    public void setActiveFlag(Boolean activeFlag) { this.activeFlag = activeFlag; }

    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }

    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }
}
