package com.example.starter.rt.dto;

/**
 * 设备能力变更结果。
 *
 * @param deviceId  设备ID
 * @param maxLevel  变更后的可执行复杂度等级上限
 * @param version   变更后的乐观锁版本
 * @param requestId 幂等键
 * @param replayed  是否为同键同参重放
 */
public record DeviceCapabilityResponse(
        String deviceId,
        Integer maxLevel,
        Integer version,
        String requestId,
        boolean replayed) {
}
