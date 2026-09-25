package com.example.starter.epidemic.api;

import jakarta.validation.constraints.NotBlank;

/**
 * 初审请求。
 */
public record ReviewRequest(
        @NotBlank(message = "reviewer 不能为空")
        String reviewer,
        String opinion
) {
}
