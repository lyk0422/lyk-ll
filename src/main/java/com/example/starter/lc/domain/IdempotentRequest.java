package com.example.starter.lc.domain;

import java.time.LocalDateTime;

/**
 * 幂等请求记录：同一 requestId 只保存首次结果。
 */
public class IdempotentRequest {

    /** 客户端幂等键。 */
    private String requestId;
    private OperationType operationType;
    /** requestId 与全部业务字段（不符点按单据类型排序规范化）的 SHA-256 指纹。 */
    private String fingerprint;
    /** 首次响应 HTTP 状态码。 */
    private Integer httpStatus;
    /** 首次响应体 JSON。 */
    private String responseBody;
    private LocalDateTime createdAtUtc;

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public OperationType getOperationType() {
        return operationType;
    }

    public void setOperationType(OperationType operationType) {
        this.operationType = operationType;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public void setFingerprint(String fingerprint) {
        this.fingerprint = fingerprint;
    }

    public Integer getHttpStatus() {
        return httpStatus;
    }

    public void setHttpStatus(Integer httpStatus) {
        this.httpStatus = httpStatus;
    }

    public String getResponseBody() {
        return responseBody;
    }

    public void setResponseBody(String responseBody) {
        this.responseBody = responseBody;
    }

    public LocalDateTime getCreatedAtUtc() {
        return createdAtUtc;
    }

    public void setCreatedAtUtc(LocalDateTime createdAtUtc) {
        this.createdAtUtc = createdAtUtc;
    }
}
