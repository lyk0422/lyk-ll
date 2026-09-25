package com.example.starter.marpol.domain;

import java.time.Instant;

/**
 * requestId 幂等登记（表 emission_request）。
 */
public class EmissionRequest {

    /** 幂等键（唯一）。 */
    private String requestId;
    /** 覆盖 requestId 与全部业务字段的指纹。 */
    private String fingerprint;
    /** SUBMIT-新建提交；AMEND-修正。 */
    private String action;
    private String recordNo;
    private Integer version;
    private Instant createdAt;

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public void setFingerprint(String fingerprint) {
        this.fingerprint = fingerprint;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getRecordNo() {
        return recordNo;
    }

    public void setRecordNo(String recordNo) {
        this.recordNo = recordNo;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
