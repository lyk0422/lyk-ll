package com.example.starter.ledger.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 站间调拨调出请求（调出方发起），以 requestId 幂等。
 */
public class TransferDispatchRequest {

    /** 幂等请求标识。 */
    @NotBlank
    @Size(max = 64)
    private String requestId;

    /** 电池唯一编码。 */
    @NotBlank
    @Size(max = 64)
    private String batteryCode;

    /** 调出站点编码（发起方）。 */
    @NotBlank
    @Size(max = 64)
    private String fromStation;

    /** 调入站点编码（签收方）。 */
    @NotBlank
    @Size(max = 64)
    private String toStation;

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

    public String getFromStation() {
        return fromStation;
    }

    public void setFromStation(String fromStation) {
        this.fromStation = fromStation;
    }

    public String getToStation() {
        return toStation;
    }

    public void setToStation(String toStation) {
        this.toStation = toStation;
    }
}
