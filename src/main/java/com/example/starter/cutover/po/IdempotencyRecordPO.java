package com.example.starter.cutover.po;

import java.time.LocalDateTime;

/**
 * 幂等记录持久化对象，对应表 idempotency_record。
 */
public class IdempotencyRecordPO {

    private Long id;
    private String requestId;
    /** 作用域枚举名：APPLY/STEP_REPORT。 */
    private String scope;
    /** 请求指纹（SHA-256 十六进制）。 */
    private String fingerprint;
    private String resourceId;
    /** 首次请求响应快照（JSON）。 */
    private String responseJson;
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public String getScope() {
        return scope;
    }

    public void setScope(String scope) {
        this.scope = scope;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public void setFingerprint(String fingerprint) {
        this.fingerprint = fingerprint;
    }

    public String getResourceId() {
        return resourceId;
    }

    public void setResourceId(String resourceId) {
        this.resourceId = resourceId;
    }

    public String getResponseJson() {
        return responseJson;
    }

    public void setResponseJson(String responseJson) {
        this.responseJson = responseJson;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
