package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 帧解析请求。
 *
 * @param version     DBC 版本名称
 * @param canId       CAN ID
 * @param timestampNs 纳秒时戳
 * @param frameSeq    帧序号（单调递增）
 * @param payloadHex  十六进制负载字符串，如 "0102A0FF"
 */
public record FrameParseRequest(
        @NotBlank String version,
        @NotNull @PositiveOrZero Long canId,
        @NotNull Long timestampNs,
        @NotNull @PositiveOrZero Long frameSeq,
        @NotBlank String payloadHex) {
}
