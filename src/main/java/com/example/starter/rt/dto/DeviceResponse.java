package com.example.starter.rt.dto;

/**
 * 设备视图。
 *
 * @param deviceId   设备ID
 * @param deviceName 设备名称
 * @param maxLevel   可执行复杂度等级上限（1-3）
 * @param version    当前版本号（从1开始，成功变更加1）
 * @param requestId  本次写操作幂等键
 * @param replayed   是否幂等重放
 */
public record DeviceResponse(
        String deviceId,
        String deviceName,
        int maxLevel,
        int version,
        String requestId,
        boolean replayed) {
}
