package com.example.starter.cutover.model;

import java.time.LocalDateTime;

/**
 * 依赖阻断明细行，对应表 cutover_block。
 * 仅记录“首个阻断来源集合”：失败前置的每个直接后继记一行；传播阻断的节点不重复记源。
 */
public class CutoverBlockPO {
    private Long id;
    private String batchId;
    private String requestId;
    /** 阻断来源：进入回退/超期的失败前置。 */
    private String sourceRequest;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getBatchId() { return batchId; }
    public void setBatchId(String batchId) { this.batchId = batchId; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public String getSourceRequest() { return sourceRequest; }
    public void setSourceRequest(String sourceRequest) { this.sourceRequest = sourceRequest; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
