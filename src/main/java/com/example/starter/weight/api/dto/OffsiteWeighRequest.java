package com.example.starter.weight.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 非现场动态称重接入请求。
 *
 * @param requestId  幂等请求标识
 * @param plateNo    车牌号
 * @param laneCode   车道编码
 * @param deviceCode 称重设备编码（关联设备状态记录与校准有效期）
 * @param weighTime  过磅时刻（UTC，ISO-8601）
 * @param totalKg    实测总重（千克）
 * @param imageRef   合成影像标识；缺失（null/空白）时证据链缺项，转人工待补
 */
public record OffsiteWeighRequest(
        @NotBlank(message = "requestId 不能为空") @Size(max = 64) String requestId,
        @NotBlank(message = "plateNo 不能为空") @Size(max = 16) String plateNo,
        @NotBlank(message = "laneCode 不能为空") @Size(max = 32) String laneCode,
        @NotBlank(message = "deviceCode 不能为空") @Size(max = 32) String deviceCode,
        @NotNull(message = "weighTime 不能为空") Instant weighTime,
        @NotNull(message = "totalKg 不能为空") @Positive(message = "totalKg 必须为正数") BigDecimal totalKg,
        @Size(max = 64) String imageRef) {
}
