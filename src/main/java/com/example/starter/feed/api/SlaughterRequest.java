package com.example.starter.feed.api;

import jakarta.validation.constraints.NotBlank;

import java.time.Instant;

/**
 * 出栏申请。
 *
 * @param requestId 幂等键
 * @param batchNo   养殖批次号
 * @param applyTime 申请出栏时刻（UTC，ISO-8601）；为空时取服务端当前时刻
 */
public record SlaughterRequest(
        @NotBlank String requestId,
        @NotBlank String batchNo,
        Instant applyTime) {
}
