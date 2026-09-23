package com.example.starter.rail.dto;

import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * 激活/完成/取消操作的公共请求体。
 */
public record TransitionRequest(
        @NotNull Integer expectedVersion,
        @NotNull Instant businessTime
) {
}
