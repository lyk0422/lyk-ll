package com.example.starter.hazmat.po;

import java.time.LocalDateTime;

/**
 * 幂等记录：以 requestId 为唯一键，指纹覆盖 requestId 与全部业务字段。
 *
 * @param requestId    幂等键
 * @param fingerprint  业务指纹（SHA-256）
 * @param status       占位状态：PROCESSING 处理中；COMPLETED 已完成（含首次失败响应）
 * @param httpStatus   首次响应 HTTP 状态码；处理中为 null
 * @param responseBody 首次响应 JSON；处理中为 null
 * @param createdAt    首次创建时刻
 */
public record IdempotentRecord(
        String requestId,
        String fingerprint,
        String status,
        Integer httpStatus,
        String responseBody,
        LocalDateTime createdAt) {
}
