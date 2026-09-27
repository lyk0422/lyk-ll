package com.example.starter.rt.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 设备能力下调请求。能力只能下调不能上调；携带 expectedVersion 做乐观并发门禁，
 * 成功下调后设备版本加1。
 *
 * @param deviceId           设备ID
 * @param expectedVersion    客户端所见设备版本（从1开始），与当前不一致返回409
 * @param newCapabilityLevel 下调后的可执行最高复杂度等级（1-3，且必须低于当前等级）
 * @param requestId          幂等键
 */
public record DeviceCapabilityRequest(
        @NotBlank(message = "不能为空") String deviceId,
        @NotNull(message = "不能为空") @Positive(message = "须为正数") Integer expectedVersion,
        @NotNull(message = "不能为空")
        @Min(value = 1, message = "复杂度等级取值为1至3")
        @Max(value = 3, message = "复杂度等级取值为1至3") Integer newCapabilityLevel,
        @NotBlank(message = "不能为空") String requestId) {
}
