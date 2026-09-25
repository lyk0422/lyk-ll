package com.example.starter.ledger.po;

import java.time.Instant;

/**
 * 链不一致告警 PO。回站入站链校验失败时落库一条告警。
 */
public class ChainAlertPO {

    /** 主键。 */
    private Long id;
    /** 被拒绝入站的电池编码。 */
    private String batteryCode;
    /** 尝试入站的站点编码。 */
    private String stationCode;
    /** 告警原因（链校验失败详情）。 */
    private String reason;
    /** 触发告警的请求标识。 */
    private String requestId;
    /** 告警时间（UTC）。 */
    private Instant createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getBatteryCode() {
        return batteryCode;
    }

    public void setBatteryCode(String batteryCode) {
        this.batteryCode = batteryCode;
    }

    public String getStationCode() {
        return stationCode;
    }

    public void setStationCode(String stationCode) {
        this.stationCode = stationCode;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
