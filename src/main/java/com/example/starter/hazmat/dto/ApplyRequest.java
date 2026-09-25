package com.example.starter.hazmat.dto;

import java.time.LocalDateTime;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 运输申请请求。
 *
 * @param requestId       幂等键（申请类）
 * @param vehiclePlate    车牌号
 * @param goodsCategory   危化品类别编码（对应 goods_category 表）
 * @param routeCode       申请通行路线编码
 * @param plannedDeparture 计划发运时刻（Asia/Shanghai），用于推算各途经点计划通过时刻
 */
public record ApplyRequest(
        @NotBlank String requestId,
        @NotBlank String vehiclePlate,
        @NotBlank String goodsCategory,
        @NotBlank String routeCode,
        @NotNull LocalDateTime plannedDeparture) {
}
