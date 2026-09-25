package com.example.starter.ledger.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 换出请求，以 requestId 幂等；并发换出同一电池唯一成功。
 */
public class SwapOutRequest {

    /** 幂等请求标识。 */
    @NotBlank
    @Size(max = 64)
    private String requestId;

    /** 电池唯一编码。 */
    @NotBlank
    @Size(max = 64)
    private String batteryCode;

    /** 换出站点编码，须为电池当前所在站。 */
    @NotBlank
    @Size(max = 64)
    private String stationCode;

    /** 绑定车辆编码，换出后车辆侧“在用”。 */
    @NotBlank
    @Size(max = 64)
    private String vehicleCode;

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

    public String getVehicleCode() {
        return vehicleCode;
    }

    public void setVehicleCode(String vehicleCode) {
        this.vehicleCode = vehicleCode;
    }
}
