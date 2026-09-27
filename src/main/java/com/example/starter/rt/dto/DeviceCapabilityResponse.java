package com.example.starter.rt.dto;

/**
 * 设备能力下调结果。
 *
 * @param deviceId        设备ID
 * @param capabilityLevel 下调后的能力等级（1-3）
 * @param version         下调后的设备版本（登记为1，每次成功下调加1）
 * @param requestId       幂等键
 * @param replayed        是否为同键同参重放
 */
public record DeviceCapabilityResponse(
        String deviceId,
        Integer capabilityLevel,
        Integer version,
        String requestId,
        boolean replayed) {
}
