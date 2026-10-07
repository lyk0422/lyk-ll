package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 续行许可请求。
 *
 * @param requestId       幂等请求键
 * @param expectedVersion 期望处置版本（旧版 409）
 */
public record EnRouteResumeRequest(
        @NotBlank String requestId,
        @NotNull Integer expectedVersion) {
}
