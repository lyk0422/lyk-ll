package com.example.starter.hazmat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 设置安全接力点请求（创建接力段）。
 *
 * @param requestId   幂等键
 * @param waypointSeq 安全接力点途经点序号（计划通过时刻须早于夜间禁行起点）
 */
public record RelayPointRequest(
        @NotBlank String requestId,
        @PositiveOrZero int waypointSeq) {
}
