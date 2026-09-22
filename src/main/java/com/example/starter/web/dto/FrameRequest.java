package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * CAN 帧解析请求。
 *
 * @param version     指定的 DBC 版本
 * @param canId       CAN 标识符
 * @param timestampNs 纳秒时戳
 * @param seq         帧序号（同一 version+canId 下唯一标识帧，用于重放去重与新旧判定）
 * @param payload     帧负载（Jackson 以 Base64 编解码的字节数组），长度 0-8
 */
public record FrameRequest(
        @NotBlank String version,
        @NotNull Long canId,
        @NotNull Long timestampNs,
        @NotNull Long seq,
        @NotNull byte[] payload
) {
}
