package com.example.starter.weight.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

import com.example.starter.weight.domain.WeighMode;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 过磅上报请求。
 *
 * @param requestId   幂等请求标识
 * @param plateNo     车牌号
 * @param stationCode 检测站编码
 * @param weighTime   过磅时刻（UTC，ISO-8601）
 * @param mode        称重方式：DYNAMIC 动态 / STATIC 静态复检
 * @param totalKg     实测总重（千克）
 */
public record WeighRequest(
        @NotBlank(message = "requestId 不能为空") @Size(max = 64) String requestId,
        @NotBlank(message = "plateNo 不能为空") @Size(max = 16) String plateNo,
        @NotBlank(message = "stationCode 不能为空") @Size(max = 32) String stationCode,
        @NotNull(message = "weighTime 不能为空") Instant weighTime,
        @NotNull(message = "mode 不能为空") WeighMode mode,
        @NotNull(message = "totalKg 不能为空") @Positive(message = "totalKg 必须为正数") BigDecimal totalKg) {
}
