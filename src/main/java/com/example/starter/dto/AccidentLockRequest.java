package com.example.starter.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 事故锁定请求。
 *
 * @param requestId 幂等键
 * @param operator  告警操作人
 */
public record AccidentLockRequest(
        @NotBlank String requestId,
        @NotBlank String operator) {
}
