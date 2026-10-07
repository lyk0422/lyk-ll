package com.example.starter.runway.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 施工单修改请求：仅未生效单据可整体替换施工段；已生效不得缩段。expectedVersion 乐观锁。
 *
 * @param requestId       幂等请求标识
 * @param expectedVersion 期望的施工单聚合版本，不符返回409
 * @param segments        新施工段集合：1至10个不同段号，单内区间不得重叠（端点相接允许）
 */
public record ConstructionOrderModifyRequest(
        @NotBlank String requestId,
        int expectedVersion,
        @NotEmpty @Size(max = 10) List<@Valid ConstructionSegmentInput> segments) {
}
