package com.example.starter.transition.model;

import java.time.LocalDateTime;

/**
 * 幂等请求记录：指纹覆盖 requestId 与全部业务字段。
 */
public class IdempotentRequest {

    private String requestId;
    /** APPLY_ORDER / SUBMIT_INSPECTION / SUBMIT_REEXAM。 */
    private String bizType;
    /** SHA-256 指纹。 */
    private String fingerprint;
    /** 首次结果引用（单号/卷号）。 */
    private String resultRef;
    /** 首次响应 JSON 原文，重放原样返回。 */
    private String resultJson;
    private LocalDateTime createdAt;

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public String getBizType() {
        return bizType;
    }

    public void setBizType(String bizType) {
        this.bizType = bizType;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public void setFingerprint(String fingerprint) {
        this.fingerprint = fingerprint;
    }

    public String getResultRef() {
        return resultRef;
    }

    public void setResultRef(String resultRef) {
        this.resultRef = resultRef;
    }

    public String getResultJson() {
        return resultJson;
    }

    public void setResultJson(String resultJson) {
        this.resultJson = resultJson;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
