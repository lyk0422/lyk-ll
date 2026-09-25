package com.example.starter.ledger.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 回站入站请求，以 requestId 幂等。
 */
public class InboundRequest {

    /** 幂等请求标识。 */
    @NotBlank
    @Size(max = 64)
    private String requestId;

    /** 电池唯一编码。 */
    @NotBlank
    @Size(max = 64)
    private String batteryCode;

    /** 入站站点编码。 */
    @NotBlank
    @Size(max = 64)
    private String stationCode;

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
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
}
