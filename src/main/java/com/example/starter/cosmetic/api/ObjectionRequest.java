package com.example.starter.cosmetic.api;

import jakarta.validation.constraints.NotBlank;

/**
 * 公示异议请求。
 */
public record ObjectionRequest(@NotBlank(message = "异议内容必填") String content) {
}
