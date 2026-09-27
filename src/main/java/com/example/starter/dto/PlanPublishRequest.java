package com.example.starter.dto;

import java.time.LocalDate;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 日前发电计划发布请求。
 * 计划按风场＋UTC 日期编制，须含完整 96 个 15 分钟半开时段及每台机组的非负整数 kW 计划；
 * 缺段、重复机组或超过合成装机容量整版拒绝。
 *
 * @param requestId       幂等键，覆盖本对象全部业务字段（含版本、集合与精确时刻）
 * @param farmCode        风场编码
 * @param planDate        计划 UTC 日期
 * @param expectedVersion 期望的当前版本号（无已发布版本时为 0），不匹配则 409
 * @param slots           96 个时段计划，slotIndex 0-95 各一段
 */
public record PlanPublishRequest(
        @NotBlank String requestId,
        @NotBlank String farmCode,
        @NotNull LocalDate planDate,
        @NotNull @Min(0) Integer expectedVersion,
        @NotNull @Valid List<SlotPlan> slots) {

    /** 单时段计划：该时段内风场每台机组一条。 */
    public record SlotPlan(
            @NotNull @Min(0) @Max(95) Integer slotIndex,
            @NotNull @Valid List<TurbinePlan> turbines) {
    }

    /** 单机组计划值：非负整数 kW，不得超过机组装机容量。 */
    public record TurbinePlan(
            @NotBlank String turbineCode,
            @NotNull @PositiveOrZero Integer plannedKw) {
    }
}
