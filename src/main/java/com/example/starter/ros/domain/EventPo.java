package com.example.starter.ros.domain;

import java.time.Instant;

/**
 * resource_event 表行模型。
 *
 * @param revision            全局递增事件版本
 * @param nodeId              所属节点
 * @param eventType           事件类型
 * @param resourceCategory    资源类别，节点级事件为 null
 * @param fullyQualifiedName  全限定资源名，节点级事件为 null
 * @param interfaceType       接口类型，节点级事件为 null
 * @param detail              附加说明
 * @param createdAt           事件产生时间
 */
public record EventPo(
        long revision,
        String nodeId,
        String eventType,
        ResourceCategory resourceCategory,
        String fullyQualifiedName,
        String interfaceType,
        String detail,
        Instant createdAt) {
}
