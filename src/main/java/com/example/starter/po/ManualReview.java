package com.example.starter.po;

import java.time.Instant;

/**
 * 人工复核台账 PO（表 manual_review）。
 * 单元 24 小时(UTC)内调节超出 3 次时，调节令不执行并转入此表。
 */
public class ManualReview {
    private Long id;
    /** 换热站ID */
    private Long stationId;
    /** 转入复核的单元ID */
    private Long unitId;
    /** 触发复核的请求幂等键 */
    private String requestId;
    /** 窗口内已发生调节次数 */
    private Integer adjustmentCount;
    /** 24 小时窗口起点(UTC) */
    private Instant windowStart;
    /** 24 小时窗口终点(UTC) */
    private Instant windowEnd;
    /** 复核原因 */
    private String reason;
    /** 转入时刻(UTC) */
    private Instant createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getStationId() { return stationId; }
    public void setStationId(Long stationId) { this.stationId = stationId; }
    public Long getUnitId() { return unitId; }
    public void setUnitId(Long unitId) { this.unitId = unitId; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public Integer getAdjustmentCount() { return adjustmentCount; }
    public void setAdjustmentCount(Integer adjustmentCount) { this.adjustmentCount = adjustmentCount; }
    public Instant getWindowStart() { return windowStart; }
    public void setWindowStart(Instant windowStart) { this.windowStart = windowStart; }
    public Instant getWindowEnd() { return windowEnd; }
    public void setWindowEnd(Instant windowEnd) { this.windowEnd = windowEnd; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
