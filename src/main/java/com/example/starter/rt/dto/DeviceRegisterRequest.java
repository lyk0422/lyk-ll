package com.example.starter.rt.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 合成设备登记请求。登记成功版本从 1 开始。
 *
 * @param deviceId   设备ID（合成）
 * @param deviceName 设备名称（合成）
 * @param maxLevel   可执行复杂度等级上限（1-3）
 * @param requestId  幂等键
 */
public record DeviceRegisterRequest(
        @NotBlank(message = "不能为空") String deviceId,
        @NotBlank(message = "不能为空") String deviceName,
        @NotNull(message = "不能为空") @Min(value = 1, message = "等级须为1-3")
        @Max(value = 3, message = "等级须为1-3") Integer maxLevel,
        @NotBlank(message = "不能为空") String requestId) {
}
