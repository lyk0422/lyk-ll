package com.example.starter.epidemic.api;

import jakarta.validation.constraints.NotBlank;

/**
 * 终审归档请求。
 */
public record ArchiveRequest(
        @NotBlank(message = "reviewer 不能为空")
        String reviewer,
        String opinion
) {
}
