package com.example.starter.deicing.db;

import java.time.Instant;

/**
 * 车位占用记录表 deicing_allocation 的 PO，占用区间为半开 [startTime, endTime)。
 */
public class DeicingAllocationPo {

    private long id;
    /** 关联的申请幂等键。 */
    private String requestId;
    /** 航班号冗余，便于时间线展示。 */
    private String flightNumber;
    private String bayCode;
    /** 占用开始时刻（UTC，含）。 */
    private Instant startTime;
    /** 占用结束时刻（UTC，不含）；端点相接不冲突。 */
    private Instant endTime;
    /** 占用时的记录状态，用于时间线展示。 */
    private String status;
    private Instant createdAt;

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

    public String getFlightNumber() {
        return flightNumber;
    }

    public void setFlightNumber(String flightNumber) {
        this.flightNumber = flightNumber;
    }

    public String getBayCode() {
        return bayCode;
    }

    public void setBayCode(String bayCode) {
        this.bayCode = bayCode;
    }

    public Instant getStartTime() {
        return startTime;
    }

    public void setStartTime(Instant startTime) {
        this.startTime = startTime;
    }

    public Instant getEndTime() {
        return endTime;
    }

    public void setEndTime(Instant endTime) {
        this.endTime = endTime;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
