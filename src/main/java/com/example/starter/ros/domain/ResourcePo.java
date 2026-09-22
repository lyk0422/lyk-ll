package com.example.starter.ros.domain;

import java.time.Instant;

/**
 * node_resource 表行模型。
 *
 * @param id                  自增主键
 * @param nodeId              归属节点
 * @param resourceCategory    资源类别
 * @param fullyQualifiedName  全限定名称
 * @param interfaceType       接口类型
 * @param createdAt           创建时间
 */
public record ResourcePo(
        Long id,
        String nodeId,
        ResourceCategory resourceCategory,
        String fullyQualifiedName,
        String interfaceType,
        Instant createdAt) {
}
