package com.example.starter.arbitration.api;

import jakarta.validation.constraints.NotBlank;

/**
 * 租约释放请求体。
 *
 * @param controllerId 持有方控制器标识
 * @param requestId    原申请使用的幂等键
 */
public record ReleaseLeaseRequest(
        @NotBlank String controllerId,
        @NotBlank String requestId) {
}
