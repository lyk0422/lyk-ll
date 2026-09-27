package com.example.starter.cutover.model;

import java.time.LocalDateTime;

/**
 * 批次有向依赖边冻结对象，对应表 cutover_batch_edge。
 * fromRequest -> toRequest：toRequest 开始执行前 fromRequest 必须已验证通过并封板。
 */
public class CutoverBatchEdgePO {
    private Long id;
    private String batchId;
    private String fromRequest;
    private String toRequest;
    private int ord;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getBatchId() { return batchId; }
    public void setBatchId(String batchId) { this.batchId = batchId; }
    public String getFromRequest() { return fromRequest; }
    public void setFromRequest(String fromRequest) { this.fromRequest = fromRequest; }
    public String getToRequest() { return toRequest; }
    public void setToRequest(String toRequest) { this.toRequest = toRequest; }
    public int getOrd() { return ord; }
    public void setOrd(int ord) { this.ord = ord; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
