package com.example.starter.cutover.model;

import java.time.LocalDateTime;

/**
 * 依赖阻断明细，对应表 cutover_block_record。
 * 记录申请被阻断时的首个阻断来源集合，落库后不改写。
 */
public class CutoverBlockRecordPO {
    private Long id;
    private String batchId;
    /** 被阻断（转 DEPENDENCY_BLOCKED）的申请 ID。 */
    private String requestId;
    /** 阻断来源：处于回退/超期/阻断态的直接前置申请 ID。 */
    private String sourceRequestId;
    /** 来源触发原因：ROLLBACK 回退 / WINDOW_EXPIRED 窗口超期 / DEPENDENCY_BLOCKED 阻断传导。 */
    private String cause;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getBatchId() { return batchId; }
    public void setBatchId(String batchId) { this.batchId = batchId; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public String getSourceRequestId() { return sourceRequestId; }
    public void setSourceRequestId(String sourceRequestId) { this.sourceRequestId = sourceRequestId; }
    public String getCause() { return cause; }
    public void setCause(String cause) { this.cause = cause; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
