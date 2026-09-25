package com.example.starter.pump.domain;

import java.time.LocalDateTime;

/**
 * 幂等请求台账记录。
 *
 * @param requestId    幂等键
 * @param bizType      业务类型 WARNING/PUMP_TASK/ARCHIVE
 * @param fingerprint  requestId+全部业务字段的SHA-256指纹
 * @param statusCode   首次受理HTTP状态码
 * @param responseBody 首次受理返回体JSON；占位插入后事务结束前必定回写
 * @param createdAt    首次受理时刻
 */
public record StoredRequest(
        String requestId,
        String bizType,
        String fingerprint,
        Integer statusCode,
        String responseBody,
        LocalDateTime createdAt
) {
}
