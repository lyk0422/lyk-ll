package com.example.starter.greenhouse.po;

import java.time.Instant;

/**
 * requestId 幂等记录：指纹覆盖 requestId 与全部业务字段。
 * 同键同参返回首次结果；同键异参返回 409。
 */
public class IdempotentRequest {
    private String requestId;
    private String bizType;
    private String fingerprint;
    private int httpStatus;
    private String responseBody;
    private Instant createdAt;

    public String getRequestId() { return requestId; }
    public void setRequestId(String v) { this.requestId = v; }
    public String getBizType() { return bizType; }
    public void setBizType(String v) { this.bizType = v; }
    public String getFingerprint() { return fingerprint; }
    public void setFingerprint(String v) { this.fingerprint = v; }
    public int getHttpStatus() { return httpStatus; }
    public void setHttpStatus(int v) { this.httpStatus = v; }
    public String getResponseBody() { return responseBody; }
    public void setResponseBody(String v) { this.responseBody = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
}
