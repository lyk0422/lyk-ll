package com.example.starter.cutover.model;

import java.time.LocalDateTime;

/**
 * 割接申请持久化对象，对应表 cutover_request。
 */
public class CutoverRequestPO {
    private Long id;
    private String requestId;
    private String title;
    private String deviceCode;
    private String priority;
    private LocalDateTime windowStart;
    private LocalDateTime windowEnd;
    private String status;
    private String impactSystems;
    private String fingerprint;
    private String rejectReason;
    private LocalDateTime execStartedAt;
    private String rollbackCause;
    private long versionNo = 1L;
    /** 当前绑定的未结束批次 ID；null 表示不属于任何未结束批次。 */
    private String openBatchId;
    private boolean blockedFlag;
    /** 首个阻断来源集合，逗号分隔按申请编号排序；null 表示未阻断。 */
    private String blockedSources;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDeviceCode() { return deviceCode; }
    public void setDeviceCode(String deviceCode) { this.deviceCode = deviceCode; }
    public String getPriority() { return priority; }
    public void setPriority(String priority) { this.priority = priority; }
    public LocalDateTime getWindowStart() { return windowStart; }
    public void setWindowStart(LocalDateTime windowStart) { this.windowStart = windowStart; }
    public LocalDateTime getWindowEnd() { return windowEnd; }
    public void setWindowEnd(LocalDateTime windowEnd) { this.windowEnd = windowEnd; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getImpactSystems() { return impactSystems; }
    public void setImpactSystems(String impactSystems) { this.impactSystems = impactSystems; }
    public String getFingerprint() { return fingerprint; }
    public void setFingerprint(String fingerprint) { this.fingerprint = fingerprint; }
    public String getRejectReason() { return rejectReason; }
    public void setRejectReason(String rejectReason) { this.rejectReason = rejectReason; }
    public LocalDateTime getExecStartedAt() { return execStartedAt; }
    public void setExecStartedAt(LocalDateTime execStartedAt) { this.execStartedAt = execStartedAt; }
    public String getRollbackCause() { return rollbackCause; }
    public void setRollbackCause(String rollbackCause) { this.rollbackCause = rollbackCause; }
    public long getVersionNo() { return versionNo; }
    public void setVersionNo(long versionNo) { this.versionNo = versionNo; }
    public String getOpenBatchId() { return openBatchId; }
    public void setOpenBatchId(String openBatchId) { this.openBatchId = openBatchId; }
    public boolean isBlockedFlag() { return blockedFlag; }
    public void setBlockedFlag(boolean blockedFlag) { this.blockedFlag = blockedFlag; }
    public String getBlockedSources() { return blockedSources; }
    public void setBlockedSources(String blockedSources) { this.blockedSources = blockedSources; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
