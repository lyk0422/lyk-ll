package com.example.starter.rt.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 连续分次批量排程发布请求：单批最多 10 个连续分次，任一冲突整批零占用。
 *
 * @param patientId 患者ID
 * @param planCode  计划业务编码（须为已批准且首次验证合格的当前计划）
 * @param level     排程登记复杂度等级：1/2/3，对应每分次10/20/30分钟
 * @param deviceId  占用设备ID
 * @param fractions 连续分次及其 UTC 半开占用窗口（1至10个）
 * @param requestId 发布幂等键
 */
public record SchedulePublishRequest(
        @NotBlank(message = "不能为空") String patientId,
        @NotBlank(message = "不能为空") String planCode,
        @NotNull(message = "不能为空") @Min(value = 1, message = "等级须为1至3")
        @Max(value = 3, message = "等级须为1至3") Integer level,
        @NotBlank(message = "不能为空") String deviceId,
        @NotEmpty(message = "不能为空") @Size(max = 10, message = "单批最多10个连续分次")
        List<@Valid ScheduleFractionItem> fractions,
        @NotBlank(message = "不能为空") String requestId) {
}
