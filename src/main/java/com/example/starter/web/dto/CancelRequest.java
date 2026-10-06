package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 取消请求。
 *
 * @param requestId 幂等键
 * @param reason    取消原因
 */
public record CancelRequest(
        @NotBlank @Size(max = 128) String requestId,
        @Size(max = 255) String reason) {
}
