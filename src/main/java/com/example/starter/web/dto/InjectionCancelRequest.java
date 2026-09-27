package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;

/**
 * 加注计划取消请求：只释放尚未执行的预占。
 *
 * @param requestId       幂等键
 * @param planId          加注计划ID
 * @param cancelledAt     取消时刻（业务时间）
 * @param expectedVersion 期望的储罐版本
 */
public record InjectionCancelRequest(
        @NotBlank String requestId,
        @NotNull Long planId,
        @NotNull LocalDateTime cancelledAt,
        @NotNull Integer expectedVersion) {
}
