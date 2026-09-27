package com.example.starter.cutover.model;

import java.time.LocalDateTime;

/**
 * 批次成员冻结对象，对应表 cutover_batch_member。
 */
public class CutoverBatchMemberPO {
    private Long id;
    private String batchId;
    private String requestId;
    private long expectedVersion;
    private LocalDateTime windowStart;
    private LocalDateTime windowEnd;
    private int ord;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getBatchId() { return batchId; }
    public void setBatchId(String batchId) { this.batchId = batchId; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public long getExpectedVersion() { return expectedVersion; }
    public void setExpectedVersion(long expectedVersion) { this.expectedVersion = expectedVersion; }
    public LocalDateTime getWindowStart() { return windowStart; }
    public void setWindowStart(LocalDateTime windowStart) { this.windowStart = windowStart; }
    public LocalDateTime getWindowEnd() { return windowEnd; }
    public void setWindowEnd(LocalDateTime windowEnd) { this.windowEnd = windowEnd; }
    public int getOrd() { return ord; }
    public void setOrd(int ord) { this.ord = ord; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
