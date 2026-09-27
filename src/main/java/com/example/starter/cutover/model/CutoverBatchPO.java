package com.example.starter.cutover.model;

import java.time.LocalDateTime;

/**
 * 割接依赖批次持久化对象，对应表 cutover_batch。发布即冻结，不可变更。
 */
public class CutoverBatchPO {
    private Long id;
    private String batchId;
    private String fingerprint;
    private LocalDateTime publishedAt;
    private String resultSnapshot;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getBatchId() { return batchId; }
    public void setBatchId(String batchId) { this.batchId = batchId; }
    public String getFingerprint() { return fingerprint; }
    public void setFingerprint(String fingerprint) { this.fingerprint = fingerprint; }
    public LocalDateTime getPublishedAt() { return publishedAt; }
    public void setPublishedAt(LocalDateTime publishedAt) { this.publishedAt = publishedAt; }
    public String getResultSnapshot() { return resultSnapshot; }
    public void setResultSnapshot(String resultSnapshot) { this.resultSnapshot = resultSnapshot; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
