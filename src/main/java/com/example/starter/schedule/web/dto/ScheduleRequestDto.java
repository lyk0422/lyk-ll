package com.example.starter.schedule.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 编排申请入参。
 *
 * @param requestId 幂等键
 * @param satelliteCode 卫星编码
 * @param stationCode 地面站编码
 * @param windowId 过境窗口标识
 * @param priority 任务优先级 1-5，5 最高
 * @param emergency 紧急插入标记：该日已发布时须为 true，走新版本发布，旧版本可查
 */
public record ScheduleRequestDto(
        @NotBlank String requestId,
        @NotBlank String satelliteCode,
        @NotBlank String stationCode,
        @NotNull Long windowId,
        @Min(1) @Max(5) int priority,
        boolean emergency) {
}
