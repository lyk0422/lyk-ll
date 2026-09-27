package com.example.starter.rt.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 设备登记请求。合成设备可执行复杂度等级 1 至 3。
 *
 * @param deviceId           设备ID（合成）
 * @param deviceName         设备名称（合成）
 * @param maxComplexityLevel 可执行复杂度等级上限：1/2/3，对应每分次10/20/30分钟
 * @param requestId          登记幂等键
 */
public record DeviceRegisterRequest(
        @NotBlank(message = "不能为空") String deviceId,
        @NotBlank(message = "不能为空") String deviceName,
        @NotNull(message = "不能为空") @Min(value = 1, message = "等级须为1至3")
        @Max(value = 3, message = "等级须为1至3") Integer maxComplexityLevel,
        @NotBlank(message = "不能为空") String requestId) {
}
