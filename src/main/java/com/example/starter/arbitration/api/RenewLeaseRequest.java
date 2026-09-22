package com.example.starter.arbitration.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * 租约续期请求体。
 *
 * @param controllerId 持有方控制器标识
 * @param requestId    原申请使用的幂等键
 * @param ttlMs        续期后有效期（毫秒），为空时使用服务默认配置
 */
public record RenewLeaseRequest(
        @NotBlank String controllerId,
        @NotBlank String requestId,
        @Min(1) Long ttlMs) {
}
