package com.example.starter.cutover.model;

import java.time.LocalDateTime;

/**
 * 割接批次持久化对象，对应表 cutover_batch。
 * 发布时原子冻结成员、依赖与窗口快照（members_json/edges_json）。
 */
public class CutoverBatchPO {
    private Long id;
    private String batchId;
    private LocalDateTime frozenAt;
    /** 冻结的成员快照 JSON。 */
    private String membersJson;
    /** 冻结的有向依赖快照 JSON。 */
    private String edgesJson;
    /** OPEN/CLOSED。 */
    private String status;
    private String fingerprint;
    /** 首次发布结果快照 JSON，供同键同参重放。 */
    private String resultSnapshot;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getBatchId() { return batchId; }
    public void setBatchId(String batchId) { this.batchId = batchId; }
    public LocalDateTime getFrozenAt() { return frozenAt; }
    public void setFrozenAt(LocalDateTime frozenAt) { this.frozenAt = frozenAt; }
    public String getMembersJson() { return membersJson; }
    public void setMembersJson(String membersJson) { this.membersJson = membersJson; }
    public String getEdgesJson() { return edgesJson; }
    public void setEdgesJson(String edgesJson) { this.edgesJson = edgesJson; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getFingerprint() { return fingerprint; }
    public void setFingerprint(String fingerprint) { this.fingerprint = fingerprint; }
    public String getResultSnapshot() { return resultSnapshot; }
    public void setResultSnapshot(String resultSnapshot) { this.resultSnapshot = resultSnapshot; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
