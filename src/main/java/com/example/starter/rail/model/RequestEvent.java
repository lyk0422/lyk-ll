package com.example.starter.rail.model;

/**
 * 申请生命周期事件（只追加，不更新）。
 */
public class RequestEvent {

    private long id;
    /** 关联申请。 */
    private String requestId;
    /** 事件类型，如 APPROVED/ACTIVATED/COMPLETED/CANCELLED/PREEMPTED。 */
    private String eventType;
    /** 迁移前状态，首个事件为 null。 */
    private RequestStatus fromStatus;
    /** 迁移后状态。 */
    private RequestStatus toStatus;
    /** 客户端携带的业务时间，UTC epoch 毫秒。 */
    private Long businessTime;
    /** 服务端记录时刻，UTC epoch 毫秒。 */
    private long occurredAt;
    /** 操作请求指纹，幂等重放时不再追加事件。 */
    private String fingerprint;
    /** 附加明细（JSON 文本，可空）。 */
    private String detail;

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public RequestStatus getFromStatus() {
        return fromStatus;
    }

    public void setFromStatus(RequestStatus fromStatus) {
        this.fromStatus = fromStatus;
    }

    public RequestStatus getToStatus() {
        return toStatus;
    }

    public void setToStatus(RequestStatus toStatus) {
        this.toStatus = toStatus;
    }

    public Long getBusinessTime() {
        return businessTime;
    }

    public void setBusinessTime(Long businessTime) {
        this.businessTime = businessTime;
    }

    public long getOccurredAt() {
        return occurredAt;
    }

    public void setOccurredAt(long occurredAt) {
        this.occurredAt = occurredAt;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public void setFingerprint(String fingerprint) {
        this.fingerprint = fingerprint;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }
}
