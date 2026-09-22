package com.example.starter.ros.web.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 节点信息响应,含当前状态、版本与资源视图。
 */
public record NodeResponse(
        String nodeId,
        String namespace,
        String nodeName,
        String state,
        long version,
        boolean crashed,
        List<ResourceView> resources,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
