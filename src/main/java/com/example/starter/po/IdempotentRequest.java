package com.example.starter.po;

import com.example.starter.domain.RequestType;

import java.time.Instant;

/**
 * 幂等请求记录 PO（表 idempotent_request），保存首次响应以便重放。
 */
public class IdempotentRequest {
    /** 幂等键 */
    private String requestId;
    /** 请求类型 ADJUST/EVALUATE/ARCHIVE/TEMPERATURE */
    private RequestType requestType;
    /** 受理状态 PENDING/COMPLETED */
    private String status;
    /** requestId + 全部业务字段的 SHA-256 指纹 */
    private String fingerprint;
    /** 换热站ID */
    private Long stationId;
    /** 首次响应 HTTP 状态码，执行中为空 */
    private Integer httpStatus;
    /** 首次响应体 JSON，执行中为空 */
    private String responseBody;
    /** 同键同参重放次数 */
    private Integer replayCount;
    /** 首次受理时刻(UTC) */
    private Instant createdAt;
    /** 首次完成时刻(UTC)，NULL 表示执行中 */
    private Instant completedAt;

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public RequestType getRequestType() { return requestType; }
    public void setRequestType(RequestType requestType) { this.requestType = requestType; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getFingerprint() { return fingerprint; }
    public void setFingerprint(String fingerprint) { this.fingerprint = fingerprint; }
    public Long getStationId() { return stationId; }
    public void setStationId(Long stationId) { this.stationId = stationId; }
    public Integer getHttpStatus() { return httpStatus; }
    public void setHttpStatus(Integer httpStatus) { this.httpStatus = httpStatus; }
    public String getResponseBody() { return responseBody; }
    public void setResponseBody(String responseBody) { this.responseBody = responseBody; }
    public Integer getReplayCount() { return replayCount; }
    public void setReplayCount(Integer replayCount) { this.replayCount = replayCount; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
}
