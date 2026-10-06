package com.example.starter.weight.domain;

/**
 * 证据链缺项转人工待补的原因。
 */
public enum PendingReason {
    /**
     * 缺合成影像标识。
     */
    MISSING_IMAGE,
    /**
     * 缺设备状态记录。
     */
    DEVICE_STATUS_MISSING,
    /**
     * 过磅时刻在设备校准有效期外，数据不得进入证据链。
     */
    DEVICE_CAL_EXPIRED
}
