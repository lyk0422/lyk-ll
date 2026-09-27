package com.example.starter.rt.dto;

import java.time.OffsetDateTime;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 批量排程中的单个分次排程项。窗口为 UTC 半开区间 [windowStartUtc, windowEndUtc)，
 * 长度须恰为复杂度等级对应固定分钟数（等级1/2/3→10/20/30分钟）。
 *
 * @param fractionNo      分次号（该分次须尚未执行且未被占用）
 * @param deviceId        设备ID
 * @param complexityLevel 冻结的复杂度等级（1-3），不得高于设备当前能力
 * @param windowStartUtc  窗口起点UTC（含）
 * @param windowEndUtc    窗口终点UTC（不含）
 */
public record ScheduleItemRequest(
        @NotNull(message = "不能为空") @Positive(message = "须为正数") Integer fractionNo,
        @NotBlank(message = "不能为空") String deviceId,
        @NotNull(message = "不能为空")
        @Min(value = 1, message = "复杂度等级取值为1至3")
        @Max(value = 3, message = "复杂度等级取值为1至3") Integer complexityLevel,
        @NotNull(message = "不能为空") OffsetDateTime windowStartUtc,
        @NotNull(message = "不能为空") OffsetDateTime windowEndUtc) {
}
