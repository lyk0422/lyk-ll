package com.example.starter.cutover.model;

import java.time.LocalDateTime;

/**
 * 割接批次持久化对象，对应表 cutover_batch。
 * 发布原子冻结成员、依赖边与窗口快照；batchId 为幂等键。
 */
public class CutoverBatchPO {
    private Long id;
    private String batchId;
    private LocalDateTime publishedAt;
    private String status;
    private String fingerprint;
    /** 批次版本号，从 1 开始，每次成功变更加 1。 */
    private Integer version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getBatchId() { return batchId; }
    public void setBatchId(String batchId) { this.batchId = batchId; }
    public LocalDateTime getPublishedAt() { return publishedAt; }
    public void setPublishedAt(LocalDateTime publishedAt) { this.publishedAt = publishedAt; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getFingerprint() { return fingerprint; }
    public void setFingerprint(String fingerprint) { this.fingerprint = fingerprint; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
