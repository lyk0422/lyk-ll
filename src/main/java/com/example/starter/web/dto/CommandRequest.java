package com.example.starter.web.dto;

import java.time.LocalDateTime;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 限电指令接收请求。issuedAt 缺省时取服务端当前时刻。
 */
public record CommandRequest(
        @NotBlank String requestId,
        @NotNull Long farmId,
        @NotNull @PositiveOrZero Integer targetKw,
        LocalDateTime issuedAt) {
}
