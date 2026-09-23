package com.example.starter.slam.api;

import com.example.starter.slam.domain.EdgeStatus;
import com.example.starter.slam.domain.EdgeType;

import java.time.Instant;

/**
 * 边写入响应。
 *
 * @param id              边 ID
 * @param requestId       客户端幂等键
 * @param type            边类型
 * @param status          审计状态：VALID 进入有效图；REJECTED 仅审计留存
 * @param graphVersion    写入后有效图版本；被拒绝的回环不触发递增，返回当前版本
 * @param duplicated      是否为同 ID 同内容的重复写入
 * @param rejectReason    拒绝原因（仅 REJECTED 时非空）
 * @param createdAt       创建时间（UTC）
 */
public record EdgeView(
        String id,
        String requestId,
        EdgeType type,
        EdgeStatus status,
        long graphVersion,
        boolean duplicated,
        String rejectReason,
        Instant createdAt
) {
}
