package com.example.starter.fermentation.dto;

import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * 降温完成进入后熟请求，指定后熟保温计时起点。
 */
public record StartMaturationRequest(@NotNull Instant maturingAt) {
}
