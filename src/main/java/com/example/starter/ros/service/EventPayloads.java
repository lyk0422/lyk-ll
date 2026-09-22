package com.example.starter.ros.service;

import java.util.List;

/**
 * 资源变更事件负载定义,JSON 序列化后写入 node_event.payload。
 */
public final class EventPayloads {

    private EventPayloads() {
    }

    /**
     * 资源条目(事件负载与检查点共用)。
     */
    public record ResourceItem(String category, String fqName, String interfaceType) {
    }

    /**
     * 节点注册事件负载。
     */
    public record RegisteredPayload(
            String nodeId,
            String namespace,
            String nodeName,
            String state,
            long version,
            List<ResourceItem> resources
    ) {
    }

    /**
     * 生命周期状态迁移事件负载。
     */
    public record StateChangedPayload(
            String nodeId,
            String fromState,
            String toState,
            long version
    ) {
    }

    /**
     * 主动注销事件负载。
     */
    public record DeregisteredPayload(String nodeId) {
    }

    /**
     * 异常退出事件负载,携带被同事务清理的全部资源。
     */
    public record CrashedPayload(
            String nodeId,
            List<ResourceItem> cleanedResources
    ) {
    }
}
