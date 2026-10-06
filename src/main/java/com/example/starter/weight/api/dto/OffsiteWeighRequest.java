package com.example.starter.weight.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 非现场动态称重接入请求（按车道与过磅时刻接入）。
 *
 * @param requestId 幂等请求标识
 * @param plateNo   车牌号
 * @param laneNo    车道号
 * @param weighTime 过磅时刻（UTC，ISO-8601）
 * @param totalKg   实测总重（千克）
 * @param imageRef  合成影像标识；缺失（null/空白）时证据链缺项，转人工待补
 * @param deviceId  称重设备编号（须存在含校准有效期的设备状态记录）
 */
public record OffsiteWeighRequest(
        @NotBlank(message = "requestId 不能为空") @Size(max = 64) String requestId,
        @NotBlank(message = "plateNo 不能为空") @Size(max = 16) String plateNo,
        @NotBlank(message = "laneNo 不能为空") @Size(max = 16) String laneNo,
        @NotNull(message = "weighTime 不能为空") Instant weighTime,
        @NotNull(message = "totalKg 不能为空") @Positive(message = "totalKg 必须为正数") BigDecimal totalKg,
        @Size(max = 128) String imageRef,
        @NotBlank(message = "deviceId 不能为空") @Size(max = 32) String deviceId) {
}
