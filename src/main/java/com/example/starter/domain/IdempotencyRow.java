package com.example.starter.domain;

/**
 * 幂等受理记录行(对应 idempotency_record 表)。
 *
 * @param requestId      幂等键
 * @param endpoint       受理的业务端点
 * @param fingerprint    请求指纹(端点+requestId+全部业务字段的SHA-256)
 * @param responseStatus 首次受理的HTTP状态码, NULL 表示处理中
 * @param responseBody   首次受理的响应体JSON, NULL 表示处理中
 */
public record IdempotencyRow(String requestId, String endpoint, String fingerprint,
                             Integer responseStatus, String responseBody) {
}
