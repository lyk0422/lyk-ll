package com.example.starter.rebate.api;

import jakarta.validation.constraints.NotBlank;

/**
 * 疑点补充说明提交请求。
 *
 * @param requestId   幂等键
 * @param explanation 补充说明内容
 */
public record ExplainRequest(
        @NotBlank String requestId,
        @NotBlank String explanation
) {
}
