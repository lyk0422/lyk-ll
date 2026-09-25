package com.example.starter.web;

import jakarta.validation.constraints.NotBlank;

/**
 * 抢修开始请求。
 */
public record RepairStartRequest(@NotBlank String requestId) {
}
