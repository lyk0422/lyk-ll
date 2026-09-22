package com.example.starter.ros.domain;

import java.time.LocalDateTime;

/**
 * 注册节点持久化对象(node_id 主键)。
 *
 * @param crashed 是否异常退出:true 表示由 crash 接口模拟的异常退出,节点行保留为墓碑
 */
public record RosNode(
        String nodeId,
        String namespace,
        String nodeName,
        LifecycleState state,
        long version,
        boolean crashed,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
