package com.example.starter.dto;

import jakarta.validation.constraints.NotNull;

/** 模拟网络通断请求。 */
public record NetworkRequest(@NotNull Boolean online) {
}
