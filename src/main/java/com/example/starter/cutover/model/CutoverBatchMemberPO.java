package com.example.starter.cutover.model;

import java.time.LocalDateTime;

/**
 * 批次成员持久化对象，对应表 cutover_batch_member。
 * 发布时冻结申请版本与窗口快照；blockSources 为首个阻断来源集合，一旦写入不再覆盖。
 */
public class CutoverBatchMemberPO {
    private Long id;
    private String batchId;
    private String requestId;
    private Integer requestVersion;
    private LocalDateTime windowStart;
    private LocalDateTime windowEnd;
    private String memberStatus;
    private String blockSources;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getBatchId() { return batchId; }
    public void setBatchId(String batchId) { this.batchId = batchId; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public Integer getRequestVersion() { return requestVersion; }
    public void setRequestVersion(Integer requestVersion) { this.requestVersion = requestVersion; }
    public LocalDateTime getWindowStart() { return windowStart; }
    public void setWindowStart(LocalDateTime windowStart) { this.windowStart = windowStart; }
    public LocalDateTime getWindowEnd() { return windowEnd; }
    public void setWindowEnd(LocalDateTime windowEnd) { this.windowEnd = windowEnd; }
    public String getMemberStatus() { return memberStatus; }
    public void setMemberStatus(String memberStatus) { this.memberStatus = memberStatus; }
    public String getBlockSources() { return blockSources; }
    public void setBlockSources(String blockSources) { this.blockSources = blockSources; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
