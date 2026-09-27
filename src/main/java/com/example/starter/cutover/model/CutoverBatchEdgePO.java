package com.example.starter.cutover.model;

import java.time.LocalDateTime;

/**
 * 批次内有向前置依赖边持久化对象，对应表 cutover_batch_edge。
 * from 为前置（须先验证通过并封板），to 为受执行门禁约束的后继。
 */
public class CutoverBatchEdgePO {
    private Long id;
    private String batchId;
    private String fromRequestId;
    private String toRequestId;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getBatchId() { return batchId; }
    public void setBatchId(String batchId) { this.batchId = batchId; }
    public String getFromRequestId() { return fromRequestId; }
    public void setFromRequestId(String fromRequestId) { this.fromRequestId = fromRequestId; }
    public String getToRequestId() { return toRequestId; }
    public void setToRequestId(String toRequestId) { this.toRequestId = toRequestId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
