package com.example.starter.cutover.model;

import java.time.LocalDateTime;

/**
 * 批次前置依赖边持久化对象，对应表 cutover_batch_edge。
 * predecessor 必须先验证通过并封板，successor 才可开始执行。
 */
public class CutoverBatchEdgePO {
    private Long id;
    private String batchId;
    private String predecessor;
    private String successor;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getBatchId() { return batchId; }
    public void setBatchId(String batchId) { this.batchId = batchId; }
    public String getPredecessor() { return predecessor; }
    public void setPredecessor(String predecessor) { this.predecessor = predecessor; }
    public String getSuccessor() { return successor; }
    public void setSuccessor(String successor) { this.successor = successor; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
