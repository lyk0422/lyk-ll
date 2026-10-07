package com.example.starter.web.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * 车辆清单创建请求：给出合成车号及 1 至 50 个不同头只标识，须全部属于本批合格集合；
 * 建立清单时全量预占，缺一项全单不占，创建后清单冻结、不可改单。
 *
 * @param requestId       幂等请求标识，全局唯一
 * @param vehicleNo       合成车号，批次内唯一
 * @param headNos         本车冻结头只标识，1 至 50 个且互不相同，须属于本批合格集合
 * @param expectedVersion 车单期望版本；创建场景无既存车单，固定传 0，成功后车单版本为 1
 * @param occurredAt      业务精确时刻（UTC ISO-8601），参与结构化指纹
 */
public record VehicleCreateRequest(
        @NotBlank String requestId,
        @NotBlank String vehicleNo,
        @NotEmpty @Size(min = 1, max = 50) List<@NotBlank String> headNos,
        @NotNull @PositiveOrZero Integer expectedVersion,
        @NotBlank String occurredAt) {
}
