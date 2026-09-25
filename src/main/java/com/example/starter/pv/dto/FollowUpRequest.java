package com.example.starter.pv.dto;

import java.time.Instant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 随访提交请求。
 *
 * @param submittedAt 提交时刻（UTC），为空时取服务器时钟；仅供演示与测试注入
 */
public record FollowUpRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 2048) String narrative,
        Instant submittedAt) {
}
