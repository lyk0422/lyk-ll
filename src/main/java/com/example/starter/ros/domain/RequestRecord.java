package com.example.starter.ros.domain;

import java.time.LocalDateTime;

/**
 * 幂等请求记录:同一 requestId 首次成功处理后保存响应快照。
 */
public record RequestRecord(
        String requestId,
        String nodeId,
        String op,
        String requestHash,
        String responseJson,
        LocalDateTime createdAt
) {
}
