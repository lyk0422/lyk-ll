package com.example.starter.rt.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 排程取消请求：未开始项取消并释放占用，已完成项保留实绩。
 *
 * @param expectedVersion 期望的当前排程版本号
 * @param requestId       幂等键
 */
public record ScheduleCancelRequest(
        @NotNull(message = "不能为空") @Positive(message = "须为正数") Integer expectedVersion,
        @NotBlank(message = "不能为空") String requestId) {
}
