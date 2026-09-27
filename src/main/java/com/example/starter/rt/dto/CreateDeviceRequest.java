package com.example.starter.rt.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 合成设备登记请求。
 *
 * @param deviceId   设备ID（合成）
 * @param deviceName 设备名称（合成）
 * @param maxLevel   可执行复杂度等级上限：1/2/3
 */
public record CreateDeviceRequest(
        @NotBlank(message = "不能为空") String deviceId,
        @NotBlank(message = "不能为空") String deviceName,
        @NotNull(message = "不能为空") @Min(value = 1, message = "等级须为1至3")
        @Max(value = 3, message = "等级须为1至3") Integer maxLevel) {
}
