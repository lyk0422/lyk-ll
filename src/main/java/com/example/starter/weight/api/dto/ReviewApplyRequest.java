package com.example.starter.weight.api.dto;

import java.time.Instant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 复检申诉请求：按“车牌+检测站+过磅时刻”定位案件。
 *
 * @param requestId   幂等请求标识
 * @param plateNo     车牌号
 * @param stationCode 检测站编码
 * @param weighTime   过磅时刻（UTC，ISO-8601）
 * @param reason      申诉理由
 */
public record ReviewApplyRequest(
        @NotBlank(message = "requestId 不能为空") @Size(max = 64) String requestId,
        @NotBlank(message = "plateNo 不能为空") @Size(max = 16) String plateNo,
        @NotBlank(message = "stationCode 不能为空") @Size(max = 32) String stationCode,
        @NotNull(message = "weighTime 不能为空") Instant weighTime,
        @NotBlank(message = "reason 不能为空") @Size(max = 512) String reason) {
}
