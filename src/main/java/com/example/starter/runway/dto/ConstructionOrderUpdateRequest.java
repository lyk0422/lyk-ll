package com.example.starter.runway.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 施工单修改请求：仅未生效单可修改（整体替换段集合）；已生效不得缩段。
 *
 * @param requestId       幂等请求标识
 * @param expectedVersion 期望的施工单聚合版本；不符返回409
 * @param segments        新施工段集合（1至10个，段号不同，单内区间不重叠）
 */
public record ConstructionOrderUpdateRequest(
        @NotBlank String requestId,
        @Positive int expectedVersion,
        @NotNull @Size(min = 1, max = 10) List<SegmentInput> segments) {
}
