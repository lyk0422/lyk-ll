package com.example.starter.ride;

/**
 * 触发整机立即停运的安全装置类型。
 */
public enum SafetyDeviceType {

    /** 压杠。 */
    PRESSURE_BAR("压杠"),
    /** 安全带。 */
    SEAT_BELT("安全带"),
    /** 联锁装置。 */
    INTERLOCK("联锁装置");

    private final String displayName;

    SafetyDeviceType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
