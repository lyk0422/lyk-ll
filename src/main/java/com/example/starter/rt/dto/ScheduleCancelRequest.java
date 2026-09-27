package com.example.starter.rt.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 排程批次取消请求（乐观锁：expectedVersion 须等于当前批次版本，成功取消加1）。
 *
 * @param expectedVersion 期望的当前批次版本号
 * @param requestId       幂等键
 */
public record ScheduleCancelRequest(
        @NotNull(message = "不能为空") @Positive(message = "须为正数") Integer expectedVersion,
        @NotBlank(message = "不能为空") String requestId) {
}
