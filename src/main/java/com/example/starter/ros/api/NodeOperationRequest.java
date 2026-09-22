package com.example.starter.ros.api;

import jakarta.validation.constraints.NotBlank;

/**
 * 携带 requestId 的单节点操作请求（注销、异常退出模拟）。
 *
 * @param requestId 幂等键
 */
public record NodeOperationRequest(@NotBlank String requestId) {
}
