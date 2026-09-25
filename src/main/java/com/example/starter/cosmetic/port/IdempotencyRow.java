package com.example.starter.cosmetic.port;

import java.time.Instant;

/**
 * 幂等记录行。
 *
 * @param requestId    幂等键
 * @param operation    操作类型（SUBMIT/CHANGE/WITHDRAW/OBJECTION 等）
 * @param fingerprint  覆盖 requestId 与全部业务字段的 SHA-256 指纹
 * @param httpStatus   首次响应 HTTP 状态码
 * @param outcome      首次处理结果标识
 * @param filingNo     关联备案编号（可空）
 * @param responseJson 首次响应体快照 JSON
 * @param createdAtUtc 首次创建时刻(UTC)
 */
public record IdempotencyRow(String requestId,
                             String operation,
                             String fingerprint,
                             int httpStatus,
                             String outcome,
                             String filingNo,
                             String responseJson,
                             Instant createdAtUtc) {
}
