package com.example.starter.web;

import jakarta.validation.constraints.NotBlank;

/**
 * 研判请求：按馈线命中最高等级重要用户并计算派单优先级。
 */
public record AssessRequest(@NotBlank String requestId) {
}
