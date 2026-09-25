package com.example.starter.idempotent;

import java.time.LocalDateTime;

/**
 * 幂等受理记录。
 *
 * @param requestId    幂等键（主键）
 * @param stationId    门站ID，可空
 * @param apiType      接口类型
 * @param fingerprint  业务指纹 SHA-256
 * @param httpStatus   首次受理返回的 HTTP 状态码
 * @param resultJson   首次结果 JSON
 * @param createdAt    受理时刻
 */
public record IdempotentRecord(
        String requestId,
        Long stationId,
        String apiType,
        String fingerprint,
        int httpStatus,
        String resultJson,
        LocalDateTime createdAt) {
}
