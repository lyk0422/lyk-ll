package com.example.starter.deicing.api;

import jakarta.validation.constraints.NotBlank;

/**
 * 仅携带 requestId 的操作请求体（出队确认、作业开始、作业完成、重新除冰确认）。
 *
 * @param requestId 申请幂等键
 */
public record RequestIdRequest(@NotBlank String requestId) {
}
