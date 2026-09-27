package com.example.starter.rt.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 排程批次取消请求（乐观并发：携带 expectedVersion）。
 *
 * @param expectedVersion 期望的批次当前版本号（从1开始，成功变更加1）
 * @param requestId       取消幂等键
 */
public record ScheduleCancelRequest(
        @NotNull(message = "不能为空") @Positive(message = "须为正数") Integer expectedVersion,
        @NotBlank(message = "不能为空") String requestId) {
}
