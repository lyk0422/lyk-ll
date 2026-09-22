package com.example.starter.arbitration.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 资源租约申请。
 *
 * @param controllerId 控制器ID
 * @param requestId    幂等键：同一 requestId 同参重放返回原结果，异参重放返回409
 * @param resourceId   资源（关节/执行器）标识
 * @param priority     优先级，数值越大越高，严格大于当前持有者可抢占
 * @param leaseMs      租约时长（毫秒）
 */
public record ClaimRequest(
        @NotBlank String controllerId,
        @NotBlank String requestId,
        @NotBlank String resourceId,
        @NotNull Integer priority,
        @NotNull @Min(1) Long leaseMs) {
}
