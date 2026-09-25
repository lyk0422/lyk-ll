package com.example.starter.ledger.po;

import java.time.Instant;

/**
 * 幂等请求记录 PO。指纹覆盖 requestId 与全部业务字段。
 */
public class IdempotentRecordPO {

    /** 请求唯一标识（主键）。 */
    private String requestId;
    /** 操作类型。 */
    private String operation;
    /** 覆盖 requestId 与全部业务字段的 SHA-256 指纹。 */
    private String fingerprint;
    /** 首次响应 HTTP 状态码。 */
    private int resultStatus;
    /** 首次响应体 JSON。 */
    private String resultBody;
    /** 首次请求时间（UTC）。 */
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

    public int getResultStatus() {
        return resultStatus;
    }

    public void setResultStatus(int resultStatus) {
        this.resultStatus = resultStatus;
    }

    public String getResultBody() {
        return resultBody;
    }

    public void setResultBody(String resultBody) {
        this.resultBody = resultBody;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
