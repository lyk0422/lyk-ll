package com.example.starter.dto;

import java.time.LocalDate;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

/**
 * 日前计划发布请求。计划按风场＋UTC 日期编制，须含完整 96 个十五分钟半开时段
 * 及每台机组的非负整数 kW 计划；发布后形成不可变版本。
 *
 * @param requestId       幂等键，覆盖本对象全部业务字段
 * @param farmCode        风场编码
 * @param planDate        计划日期（UTC 日期，ISO-8601，如 2026-09-27）
 * @param expectedVersion 期望的当前版本号：0 表示尚无版本，否则须等于当前版本号；成功发布后版本加 1
 * @param turbines        每台机组的 96 段计划，须恰好覆盖风场全部机组且不重复
 */
public record PlanPublishRequest(
        @NotBlank String requestId,
        @NotBlank String farmCode,
        @NotNull LocalDate planDate,
        @Min(0) int expectedVersion,
        @NotEmpty List<@Valid TurbinePlan> turbines) {

    /**
     * 单台机组的 96 段计划。plannedKw 下标 0 对应时段 1，共 96 段，缺段/多段整版拒绝。
     *
     * @param turbineCode 机组编码（风场内唯一，列表内不得重复）
     * @param plannedKw   96 个时段的核定计划出力（kW，非负且不超过装机容量）
     */
    public record TurbinePlan(
            @NotBlank String turbineCode,
            @NotNull List<@NotNull Integer> plannedKw) {
    }
}
