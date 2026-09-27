package com.example.starter.dto;

import java.time.Instant;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 日前发电计划发布请求：按风场＋UTC 日期编制，须含完整 96 个十五分钟半开时段，
 * 每段给出该风场每台机组的非负整数 kW 计划。整版业务校验（缺段、重复机组、超合成装机、
 * 机组集合不一致）在服务层统一判为 422 整版拒绝；此处仅校验形态（非 null、非负）。
 *
 * @param requestId       幂等键，覆盖本对象全部业务字段（含精确时刻）
 * @param farmCode        风场编码
 * @param planDate        UTC 日期 yyyy-MM-dd
 * @param expectedVersion 期望的当前版本号；首次发布传 0，其后传当前版本号（成功发布后 +1）
 * @param opTime          请求精确时刻（UTC，ISO-8601），作为发布时刻，参与指纹
 * @param segments        96 个时段（业务层校验每段序号 0-95 唯一且各机组计划唯一）
 */
public record PlanPublishRequest(
        @NotBlank String requestId,
        @NotBlank String farmCode,
        @NotBlank String planDate,
        @NotNull @PositiveOrZero Integer expectedVersion,
        @NotNull Instant opTime,
        @NotNull @Valid List<SegmentInput> segments) {

    /** 单个半开时段：segmentIndex 业务层校验 0-95 唯一，entries 业务层校验机组集合。 */
    public record SegmentInput(
            @NotNull Integer segmentIndex,
            @NotNull @Valid List<EntryInput> entries) {
    }

    /** 单台机组在某段的核定计划出力（非负整数 kW，不超过该机组装机）。 */
    public record EntryInput(
            @NotBlank String turbineCode,
            @PositiveOrZero int plannedPowerKw) {
    }
}
