package com.example.starter.arbitration.api;

import jakarta.validation.constraints.NotBlank;

/**
 * 控制器心跳请求体。
 *
 * @param controllerId 上报心跳的控制器标识
 */
public record HeartbeatRequest(@NotBlank String controllerId) {
}
