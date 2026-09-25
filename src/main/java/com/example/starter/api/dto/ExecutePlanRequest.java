package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 启动已审核方案执行的请求（同窑同时刻仅一个执行中方案）。
 */
public record ExecutePlanRequest(@NotBlank String planNo) {
}
