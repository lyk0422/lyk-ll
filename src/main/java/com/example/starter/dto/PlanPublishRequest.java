package com.example.starter.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 日前计划发布请求。
 *
 * @param requestId       幂等键，覆盖本对象全部业务字段（含 expectedVersion、整版 96 段集合与精确时刻）
 * @param farmCode        风场编码
 * @param planDate        计划适用日期（UTC）
 * @param expectedVersion 期望本次成功后占用（创建）的版本号：首次发布传 1；
 *                        已存在 vN 时下一次传 N+1，成功发布后当前版本加 1，不匹配 409
 * @param operateAt       调用方给出的精确发布时刻（UTC，ISO-8601），参与结构化指纹，保证同参重放稳定
 * @param segments        整版明细：完整 96 个时段（0..95）× 全场每台机组各一条，缺段/重复机组/超装机整版拒绝（422）
 */
public record PlanPublishRequest(
        @NotBlank String requestId,
        @NotBlank String farmCode,
        @NotNull LocalDate planDate,
        @NotNull @Positive Integer expectedVersion,
        @NotNull Instant operateAt,
        @NotEmpty @Valid List<PlanSegmentInput> segments) {
}
