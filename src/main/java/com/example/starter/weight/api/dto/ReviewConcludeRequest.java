package com.example.starter.weight.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 复检结论请求：按“车牌+检测站+过磅时刻”定位案件与唯一复检申请。
 *
 * @param requestId      结论幂等请求标识
 * @param plateNo        车牌号
 * @param stationCode    检测站编码
 * @param weighTime      过磅时刻（UTC，ISO-8601）
 * @param overturn       是否推翻原处罚
 * @param recheckTotalKg 静态复检总重（千克）；推翻时必填，须与在案静态复检记录一致
 */
public record ReviewConcludeRequest(
        @NotBlank(message = "requestId 不能为空") @Size(max = 64) String requestId,
        @NotBlank(message = "plateNo 不能为空") @Size(max = 16) String plateNo,
        @NotBlank(message = "stationCode 不能为空") @Size(max = 32) String stationCode,
        @NotNull(message = "weighTime 不能为空") Instant weighTime,
        @NotNull(message = "overturn 不能为空") Boolean overturn,
        @Positive(message = "recheckTotalKg 必须为正数") BigDecimal recheckTotalKg) {
}
