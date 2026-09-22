package com.example.starter.ros.service;

import com.example.starter.ros.domain.EventType;
import com.example.starter.ros.domain.NodeEvent;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 订阅方本地资源视图投影器:即使事件以乱序或重复方式送达,
 * 也只接受 revision 严格大于已应用水位的事件,保证本地视图不会回退。
 * 本类为线程安全的内存投影,模拟订阅方进程内的本地状态机。
 */
public class ResourceViewProjector {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private long lastAppliedRevision = 0L;
    private final Set<Long> skippedRevisions = new LinkedHashSet<>();
    private final Map<String, String> nodeStates = new TreeMap<>();
    private final Map<String, Set<ResourceKey>> nodeResources = new TreeMap<>();

    /**
     * 尝试应用事件。revision 不大于当前水位(乱序旧事件或重复事件)时跳过。
     *
     * @return true 表示事件被应用;false 表示因乱序/重复被丢弃
     */
    public synchronized boolean apply(NodeEvent event) {
        if (event.revision() <= lastAppliedRevision) {
            skippedRevisions.add(event.revision());
            return false;
        }
        Payload payload = parse(event.payloadJson());
        switch (event.eventType()) {
            case REGISTERED -> {
                nodeStates.put(event.nodeId(), payload.state() == null
                        ? "UNCONFIGURED" : payload.state());
                Set<ResourceKey> keys = new LinkedHashSet<>();
                if (payload.resources() != null) {
                    for (ResourceItem item : payload.resources()) {
                        keys.add(new ResourceKey(item.category(), item.fqName(),
                                item.interfaceType()));
                    }
                }
                nodeResources.put(event.nodeId(), keys);
            }
            case STATE_CHANGED -> {
                if (payload.toState() != null) {
                    nodeStates.put(event.nodeId(), payload.toState());
                }
            }
            case DEREGISTERED, CRASHED -> {
                nodeStates.remove(event.nodeId());
                nodeResources.remove(event.nodeId());
            }
        }
        lastAppliedRevision = event.revision();
        return true;
    }

    public synchronized long lastAppliedRevision() {
        return lastAppliedRevision;
    }

    public synchronized List<Long> skippedRevisions() {
        return List.copyOf(skippedRevisions);
    }

    public synchronized String nodeState(String nodeId) {
        return nodeStates.get(nodeId);
    }

    public synchronized Set<String> knownNodeIds() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(nodeStates.keySet()));
    }

    public synchronized Set<ResourceKey> resourcesOf(String nodeId) {
        return nodeResources.getOrDefault(nodeId, Set.of())
                .stream()
                .sorted()
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    private Payload parse(String json) {
        try {
            return OBJECT_MAPPER.readValue(json, Payload.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("事件负载解析失败: " + e.getMessage(), e);
        }
    }

    /**
     * 投影器使用的资源键(类别 + 全限定名 + 接口类型)。
     */
    public record ResourceKey(String category, String fqName, String interfaceType)
            implements Comparable<ResourceKey> {
        @Override
        public int compareTo(ResourceKey other) {
            int byCategory = category.compareTo(other.category);
            if (byCategory != 0) {
                return byCategory;
            }
            int byName = fqName.compareTo(other.fqName);
            if (byName != 0) {
                return byName;
            }
            return interfaceType.compareTo(other.interfaceType);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Payload(
            String state,
            String fromState,
            String toState,
            List<ResourceItem> resources
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ResourceItem(String category, String fqName, String interfaceType) {
    }
}
