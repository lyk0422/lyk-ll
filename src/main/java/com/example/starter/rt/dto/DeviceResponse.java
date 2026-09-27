package com.example.starter.rt.dto;

/**
 * 设备登记/能力调整结果。
 *
 * @param deviceId           设备ID
 * @param deviceName         设备名称
 * @param maxComplexityLevel 当前可执行复杂度等级上限：1/2/3
 * @param version            当前版本号（登记为1，成功变更加1）
 * @param requestId          幂等键
 * @param replayed           是否为同键同参重放
 */
public record DeviceResponse(
        String deviceId,
        String deviceName,
        Integer maxComplexityLevel,
        Integer version,
        String requestId,
        boolean replayed) {
}
