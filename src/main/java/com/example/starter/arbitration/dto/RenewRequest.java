package com.example.starter.arbitration.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 租约续期请求；幂等：新到期时刻不晚于当前到期时刻时为无操作成功。
 *
 * @param controllerId 控制器ID，必须为当前持有者
 * @param resourceId   资源标识
 * @param leaseMs      续期时长（毫秒），自当前时刻起算
 */
public record RenewRequest(
        @NotBlank String controllerId,
        @NotBlank String resourceId,
        @NotNull @Min(1) Long leaseMs) {
}
