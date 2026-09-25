package com.example.starter.ledger.web;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 检测结论提交请求，以 requestId 幂等；结论提交后不可修改，复检须走新批次（新 requestId）。
 */
public class InspectionRequest {

    /** 幂等请求标识，同一检测批次的唯一标识。 */
    @NotBlank
    @Size(max = 64)
    private String requestId;

    /** 电池唯一编码。 */
    @NotBlank
    @Size(max = 64)
    private String batteryCode;

    /** SOC，百分比 0-100，阈值 90，恰好达标合格。 */
    @NotNull
    @Min(0)
    @Max(100)
    private Integer soc;

    /** 健康度，百分比 0-100，阈值 80，恰好达标合格。 */
    @NotNull
    @Min(0)
    @Max(100)
    private Integer health;

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

    public Integer getSoc() {
        return soc;
    }

    public void setSoc(Integer soc) {
        this.soc = soc;
    }

    public Integer getHealth() {
        return health;
    }

    public void setHealth(Integer health) {
        this.health = health;
    }
}
