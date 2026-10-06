package com.example.starter.domain;

import java.time.LocalDateTime;

/**
 * 幂等记录行；responseStatus/responseBody 为 NULL 表示处理中。
 */
public record IdemRecord(long id, String requestId, String fingerprint, String endpoint,
                         Integer responseStatus, String responseBody, LocalDateTime createdAt) {
}
