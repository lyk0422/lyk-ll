package com.example.starter.sync.web;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 漂移校正参数热更新请求。version 必须大于当前版本；effectiveFrom 为生效时刻(纪元毫秒)，
 * 仅作用于原始时戳不小于该值的消息。
 */
public record DriftUpdateRequest(
        @NotNull @Min(1) Integer version,
        @NotNull Long offsetMs,
        @NotNull Long effectiveFrom) {
}
