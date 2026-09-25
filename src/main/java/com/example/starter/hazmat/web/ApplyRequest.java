package com.example.starter.hazmat.web;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;

/**
 * 运输申请请求。
 *
 * @param requestId        幂等请求标识
 * @param vehicleCode      车辆编码
 * @param hazmatLevel      危化品类别等级（1 起，数字越大危险程度越高）
 * @param routeId          申请路线主键
 * @param plannedDeparture 计划发运时刻（Asia/Shanghai）
 */
public record ApplyRequest(
        @NotBlank String requestId,
        @NotBlank String vehicleCode,
        @NotNull @Min(1) Integer hazmatLevel,
        @NotNull Long routeId,
        @NotNull LocalDateTime plannedDeparture) {
}
