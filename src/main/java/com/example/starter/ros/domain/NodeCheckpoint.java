package com.example.starter.ros.domain;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 节点检查点快照,用于在当前 JVM 内恢复节点、版本与已处理 requestId。
 * 不保证跨进程持久化(内存库)。
 *
 * @param processedRequestIds 检查点保存时该节点已确认处理的幂等请求ID
 */
public record NodeCheckpoint(
        String nodeId,
        LifecycleState state,
        long version,
        String resourcesJson,
        List<String> processedRequestIds,
        LocalDateTime savedAt
) {
}
