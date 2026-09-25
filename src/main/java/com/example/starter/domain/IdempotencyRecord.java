package com.example.starter.domain;

import java.time.Instant;

/**
 * requestId 幂等记录 PO。指纹覆盖 requestId 与全部业务字段。
 */
public class IdempotencyRecord {

    private String requestId;
    /** 操作类型 DECLARE/CORRECT/ISSUE。 */
    private String operation;
    private String fingerprint;
    private int httpStatus;
    private String responseJson;
    private Instant createdAt;

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public String getOperation() {
        return operation;
    }

    public void setOperation(String operation) {
        this.operation = operation;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public void setFingerprint(String fingerprint) {
        this.fingerprint = fingerprint;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public void setHttpStatus(int httpStatus) {
        this.httpStatus = httpStatus;
    }

    public String getResponseJson() {
        return responseJson;
    }

    public void setResponseJson(String responseJson) {
        this.responseJson = responseJson;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
