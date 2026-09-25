package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 归档请求（幂等：重复归档返回首次结果）。
 */
public record ArchiveRequest(
        @NotBlank String requestId,
        @NotBlank String siloCode
) {
}
