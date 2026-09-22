package com.example.starter.ros.domain;

import java.time.Instant;

/**
 * ros_node 表行模型。
 *
 * @param nodeId         节点唯一标识
 * @param namespace      命名空间
 * @param nodeName       节点名
 * @param lifecycleState 生命周期状态
 * @param version        乐观版本号
 * @param alive          是否存活
 * @param registeredAt   注册时间
 * @param updatedAt      最近更新时间
 */
public record NodePo(
        String nodeId,
        String namespace,
        String nodeName,
        LifecycleState lifecycleState,
        long version,
        boolean alive,
        Instant registeredAt,
        Instant updatedAt) {
}
