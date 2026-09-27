package com.example.starter.cosmetic.child.port;

import java.time.Instant;

/**
 * 儿童写操作幂等记录行。
 *
 * @param requestId    幂等键
 * @param operation    操作类型（CHILD_SUBMIT/CHILD_CORRECT/CHILD_CHANGE）
 * @param fingerprint  覆盖完整参数（含版本、集合、精确时刻）的结构化 SHA-256 指纹
 * @param httpStatus   首次响应 HTTP 状态码
 * @param outcome      首次处理结果标识
 * @param filingNo     关联备案编号（可空）
 * @param responseJson 首次响应体快照 JSON
 * @param createdAtUtc 首次创建时刻(UTC)
 */
public record ChildIdempotencyRow(String requestId,
                                  String operation,
                                  String fingerprint,
                                  int httpStatus,
                                  String outcome,
                                  String filingNo,
                                  String responseJson,
                                  Instant createdAtUtc) {
}
