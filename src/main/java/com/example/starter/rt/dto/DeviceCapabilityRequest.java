package com.example.starter.rt.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 设备能力变更请求（乐观锁：expectedVersion 须等于当前版本，成功变更加1）。
 *
 * @param maxLevel        新的可执行复杂度等级上限：1/2/3
 * @param expectedVersion 期望的当前设备版本号
 * @param requestId       幂等键
 */
public record DeviceCapabilityRequest(
        @NotNull(message = "不能为空") @Min(value = 1, message = "等级须为1至3")
        @Max(value = 3, message = "等级须为1至3") Integer maxLevel,
        @NotNull(message = "不能为空") @Positive(message = "须为正数") Integer expectedVersion,
        @NotBlank(message = "不能为空") String requestId) {
}
