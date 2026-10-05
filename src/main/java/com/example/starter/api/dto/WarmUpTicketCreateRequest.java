package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 升温操作票开票请求（以 requestId 幂等）。曲线按合成版本配置执行。
 *
 * @param requestId 开票幂等键
 * @param kilnId    窑编号
 */
public record WarmUpTicketCreateRequest(
        @NotBlank String requestId,
        @NotBlank String kilnId) {
}
