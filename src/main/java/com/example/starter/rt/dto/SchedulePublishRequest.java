package com.example.starter.rt.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 分次设备时长排程批量发布请求。单次最多10个连续分次；发布冻结计划版本、等级、设备与UTC窗口，
 * 原子占用设备时长与分次资源，任一冲突整批零占用。
 *
 * @param patientId 患者ID
 * @param planCode  计划业务编码
 * @param versionNo 要引用的计划版本号（须为已批准且首次验证合格的当前版本）
 * @param requestId 发布幂等键，同批分次共享；失败不占键
 * @param items     连续分次排程项（1至10个）
 */
public record SchedulePublishRequest(
        @NotBlank(message = "不能为空") String patientId,
        @NotBlank(message = "不能为空") String planCode,
        @NotNull(message = "不能为空") @Positive(message = "须为正数") Integer versionNo,
        @NotBlank(message = "不能为空") String requestId,
        @NotEmpty(message = "至少发布一个分次排程")
        @Size(max = 10, message = "单次批量排程最多10个连续分次")
        @Valid List<ScheduleItemRequest> items) {
}
