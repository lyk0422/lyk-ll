package com.example.starter.runway.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 施工单登记请求：引用受控跑道及1至10个不同段号。requestId 为幂等标识。
 *
 * @param requestId 幂等请求标识（指纹覆盖该字段与全部业务字段）
 * @param runwayId  受控跑道编号
 * @param segments  施工段集合：1至10个不同段号，单内区间不得重叠（端点相接允许）
 */
public record ConstructionOrderCreateRequest(
        @NotBlank String requestId,
        @NotBlank String runwayId,
        @NotEmpty @Size(max = 10) List<@Valid ConstructionSegmentInput> segments) {
}
