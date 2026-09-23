package com.example.starter.rail.model;

/**
 * 幂等记录：以客户端请求键为唯一键，保存首次请求的指纹与响应。
 */
public class IdempotencyRecord {

    /** 客户端请求键（唯一）。 */
    private String requestKey;
    /** 覆盖 requestId、expectedVersion、业务时间等参数的 SHA-256 指纹。 */
    private String fingerprint;
    /** 操作类型：ACTIVATE/COMPLETE/CANCEL。 */
    private String operation;
    /** 关联申请。 */
    private String requestId;
    /** 首次响应 HTTP 状态码。 */
    private int responseCode;
    /** 首次响应体（JSON 文本）。 */
    private String responseBody;
    private long createdAt;

    public String getRequestKey() {
        return requestKey;
    }

    public void setRequestKey(String requestKey) {
        this.requestKey = requestKey;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public void setFingerprint(String fingerprint) {
        this.fingerprint = fingerprint;
    }

    public String getOperation() {
        return operation;
    }

    public void setOperation(String operation) {
        this.operation = operation;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public int getResponseCode() {
        return responseCode;
    }

    public void setResponseCode(int responseCode) {
        this.responseCode = responseCode;
    }

    public String getResponseBody() {
        return responseBody;
    }

    public void setResponseBody(String responseBody) {
        this.responseBody = responseBody;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }
}
