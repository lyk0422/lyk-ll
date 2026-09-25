package com.example.rebate.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 疑点补充说明提交请求。
 */
public record ExplainRequest(
        @NotBlank String requestId,
        @NotBlank String explanation) {
}
