package com.example.starter.rt.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 分次排程发布请求：单次批量最多 10 个连续分次，任一冲突整批零占用。
 * 发布冻结计划版本、等级、设备与窗口，原子占用资源。
 *
 * @param patientId 患者ID
 * @param planCode  计划业务编码
 * @param versionNo 排程引用的计划版本号（须为当前已批准且首次验证合格的版本）
 * @param deviceId  设备ID
 * @param level     复杂度等级：1/2/3，对应单分次 10/20/30 分钟
 * @param items     连续分次窗口集合（1 至 10 项）
 * @param requestId 幂等键
 */
public record SchedulePublishRequest(
        @NotBlank(message = "不能为空") String patientId,
        @NotBlank(message = "不能为空") String planCode,
        @NotNull(message = "不能为空") @Positive(message = "须为正数") Integer versionNo,
        @NotBlank(message = "不能为空") String deviceId,
        @NotNull(message = "不能为空") @Min(value = 1, message = "等级须为1至3")
        @Max(value = 3, message = "等级须为1至3") Integer level,
        @NotNull(message = "不能为空") @Size(min = 1, max = 10, message = "单批分次数须为1至10")
        List<@Valid ScheduleItemRequest> items,
        @NotBlank(message = "不能为空") String requestId) {
}
