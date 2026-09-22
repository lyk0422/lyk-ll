package com.example.starter.arbitration.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * 租约申请请求体。
 *
 * @param controllerId 申请控制器标识
 * @param requestId    控制器侧幂等键
 * @param priority     优先级，数值越大优先级越高
 * @param resources    申请占有的资源名列表（关节/执行器）
 * @param ttlMs        租约有效期（毫秒），为空时使用服务默认配置
 */
public record AcquireLeaseRequest(
        @NotBlank String controllerId,
        @NotBlank String requestId,
        @Min(0) int priority,
        @NotEmpty List<@NotBlank String> resources,
        @Min(1) Long ttlMs) {
}
