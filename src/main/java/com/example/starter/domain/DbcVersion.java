package com.example.starter.domain;

import java.util.Map;

/**
 * 已持久化的 DBC 版本行。
 */
public record DbcVersion(
        long id,
        String name,
        String requestId,
        String payloadDigest,
        long createdAtNano) {
}
