package com.example.starter.cutover.model;

import java.time.LocalDateTime;

/**
 * 批次成员持久化对象，对应表 cutover_batch_member。
 * 发布时冻结窗口快照；blocked_by 记录首个阻断来源根申请，一旦写入不再覆盖。
 */
public class CutoverBatchMemberPO {
    private Long id;
    private String batchId;
    private String requestId;
    private int expectedVersion;
    private LocalDateTime windowStart;
    private LocalDateTime windowEnd;
    private String blockedBy;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getBatchId() { return batchId; }
    public void setBatchId(String batchId) { this.batchId = batchId; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public int getExpectedVersion() { return expectedVersion; }
    public void setExpectedVersion(int expectedVersion) { this.expectedVersion = expectedVersion; }
    public LocalDateTime getWindowStart() { return windowStart; }
    public void setWindowStart(LocalDateTime windowStart) { this.windowStart = windowStart; }
    public LocalDateTime getWindowEnd() { return windowEnd; }
    public void setWindowEnd(LocalDateTime windowEnd) { this.windowEnd = windowEnd; }
    public String getBlockedBy() { return blockedBy; }
    public void setBlockedBy(String blockedBy) { this.blockedBy = blockedBy; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
