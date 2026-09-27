package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;

/**
 * 加注计划取消请求：只释放尚未执行的预占。
 *
 * @param planId          计划ID
 * @param requestId       幂等键
 * @param expectedVersion 储罐乐观锁期望版本
 * @param cancelledAt     取消时刻（业务时间，精确时刻参与指纹）
 */
public record TankCancelRequest(
        @NotNull Long planId,
        @NotBlank String requestId,
        @NotNull Long expectedVersion,
        @NotNull LocalDateTime cancelledAt) {
}
