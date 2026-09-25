package com.example.starter.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * 限电指令接收请求。
 *
 * @param requestId    幂等键，覆盖本对象全部业务字段
 * @param farmCode     风场编码
 * @param targetPowerKw 限电后全场目标出力（kW，正整数）
 */
public record OrderReceiveRequest(
        @NotBlank String requestId,
        @NotBlank String farmCode,
        @Positive int targetPowerKw) {
}
