package com.example.starter.runway.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 施工单登记请求：引用受控跑道及1至10个不同段号。
 *
 * @param requestId 幂等请求标识
 * @param runwayId  受控跑道编号
 * @param segments  施工段集合（1至10个，段号不同，单内区间不重叠、端点相接允许）
 */
public record ConstructionOrderCreateRequest(
        @NotBlank String requestId,
        @NotBlank String runwayId,
        @NotNull @Size(min = 1, max = 10) List<SegmentInput> segments) {
}
