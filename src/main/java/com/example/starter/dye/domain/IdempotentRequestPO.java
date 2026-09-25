package com.example.starter.dye.domain;

import java.time.LocalDateTime;

/**
 * 幂等请求登记 PO。指纹覆盖 requestId 与全部业务字段（requestId 本身也参与哈希）。
 */
public class IdempotentRequestPO {
    private String requestId;
    private OperationType operation;
    private String fingerprint;
    private int statusCode;
    private String responseClob;
    private LocalDateTime createdAt;

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public OperationType getOperation() { return operation; }
    public void setOperation(OperationType operation) { this.operation = operation; }
    public String getFingerprint() { return fingerprint; }
    public void setFingerprint(String fingerprint) { this.fingerprint = fingerprint; }
    public int getStatusCode() { return statusCode; }
    public void setStatusCode(int statusCode) { this.statusCode = statusCode; }
    public String getResponseClob() { return responseClob; }
    public void setResponseClob(String responseClob) { this.responseClob = responseClob; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
