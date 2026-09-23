package com.example.starter.playout.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 适配器 ACK 请求；ACK 可重复或迟到，只能确认对应 asRunSequence。
 *
 * @param channel       频道标识
 * @param ackKey        ACK 幂等键
 * @param asRunSequence 要确认的频道内播出序号
 */
public record AckRequest(
        @NotBlank String channel,
        @NotBlank String ackKey,
        @NotNull Long asRunSequence) {
}
