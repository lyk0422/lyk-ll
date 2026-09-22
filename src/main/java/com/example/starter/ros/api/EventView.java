package com.example.starter.ros.api;

/**
 * 资源变更事件视图，订阅方按 revision 递增消费。
 *
 * @param revision           全局递增事件版本号
 * @param nodeId             事件所属节点
 * @param eventType          事件类型
 * @param resourceCategory   资源类别，节点级事件为 null
 * @param fullyQualifiedName 全限定资源名，节点级事件为 null
 * @param interfaceType      接口类型，节点级事件为 null
 * @param detail             附加说明
 */
public record EventView(
        long revision,
        String nodeId,
        String eventType,
        String resourceCategory,
        String fullyQualifiedName,
        String interfaceType,
        String detail) {
}
