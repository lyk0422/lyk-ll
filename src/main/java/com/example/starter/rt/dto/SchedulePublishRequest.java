package com.example.starter.rt.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 排程批量发布请求：单次批量最多 10 个连续分次，任一冲突整批零占用。
 * 发布冻结计划版本、等级、设备与窗口。
 *
 * @param patientId 患者ID
 * @param planCode  计划业务编码
 * @param level     复杂度等级（1-3，对应每分次10/20/30分钟）
 * @param deviceId  占用设备ID
 * @param items     分次窗口集合（1-10 项，分次号连续）
 * @param requestId 幂等键
 */
public record SchedulePublishRequest(
        @NotBlank(message = "不能为空") String patientId,
        @NotBlank(message = "不能为空") String planCode,
        @NotNull(message = "不能为空") @Min(value = 1, message = "等级须为1-3")
        @Max(value = 3, message = "等级须为1-3") Integer level,
        @NotBlank(message = "不能为空") String deviceId,
        @NotNull(message = "不能为空") @Size(min = 1, max = 10, message = "单次批量1-10个分次")
        List<@Valid ScheduleItemRequest> items,
        @NotBlank(message = "不能为空") String requestId) {
}
