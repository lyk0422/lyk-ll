package com.example.starter.rt.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 合成设备登记请求。登记一次性完成，初始可执行复杂度等级为 1-3（合成规则，非临床能力）。
 *
 * @param deviceId        合成设备ID
 * @param deviceName      合成设备名称
 * @param capabilityLevel 初始可执行最高复杂度等级（1-3）
 */
public record DeviceRegisterRequest(
        @NotBlank(message = "不能为空") String deviceId,
        @NotBlank(message = "不能为空") String deviceName,
        @NotNull(message = "不能为空")
        @Min(value = 1, message = "复杂度等级取值为1至3")
        @Max(value = 3, message = "复杂度等级取值为1至3") Integer capabilityLevel) {
}
