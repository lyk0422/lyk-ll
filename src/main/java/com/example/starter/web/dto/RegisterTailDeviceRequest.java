package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * 列尾装置建档请求。
 *
 * @param requestId          幂等请求键
 * @param deviceNo           装置编号（合成数据）
 * @param selfTestValidUntil 自检合格有效截止时刻（UTC）；恰达截止即失效
 */
public record RegisterTailDeviceRequest(
        @NotBlank String requestId,
        @NotBlank String deviceNo,
        @NotNull Instant selfTestValidUntil) {
}
