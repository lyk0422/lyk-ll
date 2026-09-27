package com.example.starter.rt.dto;

/**
 * 设备视图。
 *
 * @param deviceId   设备ID
 * @param deviceName 设备名称
 * @param maxLevel   当前可执行复杂度等级上限：1/2/3
 * @param version    乐观锁版本，从1开始，成功变更加1
 */
public record DeviceResponse(
        String deviceId,
        String deviceName,
        Integer maxLevel,
        Integer version) {
}
