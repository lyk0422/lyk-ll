package com.example.starter.cutover.model;

import java.time.LocalDateTime;

/**
 * 批次成员持久化对象，对应表 cutover_batch_member。
 * 发布时冻结申请版本号与窗口快照；一项申请最多属于一个未结束批次。
 */
public class CutoverBatchMemberPO {
    private Long id;
    private String batchId;
    private String requestId;
    /** 发布时冻结的申请版本号（与发布入参 expectedVersion 一致）。 */
    private Integer requestVersion;
    /** 窗口开始快照（含），Asia/Shanghai。 */
    private LocalDateTime windowStart;
    /** 窗口结束快照（不含），Asia/Shanghai。 */
    private LocalDateTime windowEnd;
    private LocalDateTime createdAt;

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
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
