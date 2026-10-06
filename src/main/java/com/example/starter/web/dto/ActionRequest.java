package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 仅携带幂等键的写操作请求（确认/执行/核销）。
 *
 * @param requestId 幂等键
 */
public record ActionRequest(@NotBlank @Size(max = 128) String requestId) {
}
