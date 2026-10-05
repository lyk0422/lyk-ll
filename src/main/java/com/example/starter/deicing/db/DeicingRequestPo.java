package com.example.starter.deicing.db;

import com.example.starter.deicing.domain.DeicingType;
import com.example.starter.deicing.domain.RequestStatus;
import java.time.Instant;

/**
 * 除冰排队记录表 deicing_request 的 PO。所有时刻均为 UTC。
 */
public class DeicingRequestPo {

    private long id;
    /** 幂等键。 */
    private String requestId;
    /** 申请序号，自增，同刻到达时定序。 */
    private long seq;
    private String flightNumber;
    private String standCode;
    private DeicingType type;
    /** 所需车位数。 */
    private int requiredBays;
    /** 除冰时长，分钟。 */
    private int durationMinutes;
    /** 目标起飞时刻（UTC）。 */
    private Instant targetTakeoffTime;
    /** 截止时刻 = 目标起飞时刻减除冰时长（UTC）。 */
    private Instant deadline;
    private RequestStatus status;
    /** 排定作业开始时刻（UTC）。 */
    private Instant scheduledStart;
    /** 排定作业结束时刻（UTC）。 */
    private Instant scheduledEnd;
    /** 实际作业开始时刻（UTC），空表示尚未开始。 */
    private Instant actualStart;
    /** 实际作业完成时刻（UTC），空表示尚未登记完成；一步法据此结算库存。 */
    private Instant actualEnd;
    /** 两步法防冰液保持时间（分钟），一步法为空。 */
    private Integer hotMinutes;
    /** HOT 风险成因，{@link com.example.starter.deicing.service.HotRiskReason} 名称，空表示无风险。 */
    private String hotRiskReason;
    /** HOT 风险时给出的最早可重排时刻（UTC），空表示无风险。 */
    private Instant earliestReassignTime;
    /** 实际起飞回报时刻（UTC），空表示未回报。 */
    private Instant actualTakeoffTime;
    /** 幂等指纹：requestId 与全部业务字段的 SHA-256。 */
    private String fingerprint;
    private Instant createdAt;
    private Instant updatedAt;

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

    public long getSeq() {
        return seq;
    }

    public void setSeq(long seq) {
        this.seq = seq;
    }

    public String getFlightNumber() {
        return flightNumber;
    }

    public void setFlightNumber(String flightNumber) {
        this.flightNumber = flightNumber;
    }

    public String getStandCode() {
        return standCode;
    }

    public void setStandCode(String standCode) {
        this.standCode = standCode;
    }

    public DeicingType getType() {
        return type;
    }

    public void setType(DeicingType type) {
        this.type = type;
    }

    public int getRequiredBays() {
        return requiredBays;
    }

    public void setRequiredBays(int requiredBays) {
        this.requiredBays = requiredBays;
    }

    public int getDurationMinutes() {
        return durationMinutes;
    }

    public void setDurationMinutes(int durationMinutes) {
        this.durationMinutes = durationMinutes;
    }

    public Instant getTargetTakeoffTime() {
        return targetTakeoffTime;
    }

    public void setTargetTakeoffTime(Instant targetTakeoffTime) {
        this.targetTakeoffTime = targetTakeoffTime;
    }

    public Instant getDeadline() {
        return deadline;
    }

    public void setDeadline(Instant deadline) {
        this.deadline = deadline;
    }

    public RequestStatus getStatus() {
        return status;
    }

    public void setStatus(RequestStatus status) {
        this.status = status;
    }

    public Instant getScheduledStart() {
        return scheduledStart;
    }

    public void setScheduledStart(Instant scheduledStart) {
        this.scheduledStart = scheduledStart;
    }

    public Instant getScheduledEnd() {
        return scheduledEnd;
    }

    public void setScheduledEnd(Instant scheduledEnd) {
        this.scheduledEnd = scheduledEnd;
    }

    public Instant getActualStart() {
        return actualStart;
    }

    public void setActualStart(Instant actualStart) {
        this.actualStart = actualStart;
    }

    public Instant getActualEnd() {
        return actualEnd;
    }

    public void setActualEnd(Instant actualEnd) {
        this.actualEnd = actualEnd;
    }

    public Integer getHotMinutes() {
        return hotMinutes;
    }

    public void setHotMinutes(Integer hotMinutes) {
        this.hotMinutes = hotMinutes;
    }

    public String getHotRiskReason() {
        return hotRiskReason;
    }

    public void setHotRiskReason(String hotRiskReason) {
        this.hotRiskReason = hotRiskReason;
    }

    public Instant getEarliestReassignTime() {
        return earliestReassignTime;
    }

    public void setEarliestReassignTime(Instant earliestReassignTime) {
        this.earliestReassignTime = earliestReassignTime;
    }

    public Instant getActualTakeoffTime() {
        return actualTakeoffTime;
    }

    public void setActualTakeoffTime(Instant actualTakeoffTime) {
        this.actualTakeoffTime = actualTakeoffTime;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public void setFingerprint(String fingerprint) {
        this.fingerprint = fingerprint;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
