package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 调度单结案请求。结案为终态，重复结案返回幂等成功。
 */
public record ClosureRequest(
        @NotBlank String requestId,
        @NotBlank String orderRequestId
) {
}
