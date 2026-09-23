package com.example.starter.ackmodel;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 适配器 ACK 请求：只能确认指定频道与 asRunSequence 的记录。
 */
public record AckRequest(
        @NotBlank String channelId,
        @NotNull Long asRunSequence,
        Long atMs
) {
}
