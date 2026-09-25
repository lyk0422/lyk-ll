package com.example.starter.model;

import java.time.Instant;

/**
 * 幂等记录：同键同参返回首次结果，同键异参拒绝（409）。
 *
 * @param requestId    幂等键
 * @param apiName      接口名称
 * @param fingerprint  请求指纹，覆盖 requestId 与全部业务字段
 * @param responseBody 首次成功响应快照，JSON
 * @param createdAt    创建时间，UTC
 */
public record IdempotencyRecord(String requestId, String apiName, String fingerprint,
                                String responseBody, Instant createdAt) {
}
