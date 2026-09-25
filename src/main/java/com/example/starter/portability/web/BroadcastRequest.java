package com.example.starter.portability.web;

import jakarta.validation.constraints.NotBlank;

/**
 * 生效广播请求，requestId 为幂等键：同键同参重放首次结果，同键异参返回 409。
 */
public record BroadcastRequest(@NotBlank String requestId) {
}
