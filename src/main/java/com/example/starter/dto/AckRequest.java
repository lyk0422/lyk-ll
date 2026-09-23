package com.example.starter.dto;

import jakarta.validation.constraints.NotBlank;

/** ACK 请求；ACK 按全局唯一 dedupKey 精确确认，可重复、乱序到达。 */
public record AckRequest(@NotBlank String dedupKey) {
}
