package com.example.starter.cutover.model;

import java.time.LocalDateTime;

/**
 * 批次内有向前置依赖边，对应表 cutover_batch_edge。
 * 前置须先验证通过并封板，后继方可启动执行。
 */
public class CutoverBatchEdgePO {
    private Long id;
    private String batchId;
    /** 前置申请 ID（须先验证通过并封板）。 */
    private String predecessor;
    /** 后继申请 ID（全部直接前置封板后方可启动）。 */
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
