package com.example.starter.ros.service;

import com.example.starter.ros.api.EventView;
import com.example.starter.ros.api.ResourceView;
import com.example.starter.ros.domain.EventTypes;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 订阅方本地资源视图投影。
 *
 * <p>消费规则：
 * <ul>
 *     <li>仅接受 revision = lastRevision + 1 的事件（严格递增、无缺号）；</li>
 *     <li>revision &le; lastRevision 的重复/乱序事件直接忽略，本地视图不回退；</li>
 *     <li>revision &gt; lastRevision + 1 的跳号事件拒绝（存在缺口），不应用；</li>
 *     <li>RESOURCE_DECLARED 增加资源；RESOURCE_RELEASED/NODE_CRASH_CLEANUP 释放资源。</li>
 * </ul>
 */
@Component
public class SubscriberProjection {

    private final ReentrantLock lock = new ReentrantLock();
    private long lastRevision;
    /** nodeId -> (资源键 -> 资源视图) */
    private final Map<String, Map<String, ResourceView>> resourcesByNode = new ConcurrentHashMap<>();

    /**
     * 投递一批可能乱序或重复的事件，返回处理结果。
     *
     * @param events 订阅通道收到的事件（顺序可能被打乱或含重复）
     * @return 应用/忽略/拒绝统计与处理后的视图快照
     */
    public DeliveryResult deliver(List<EventView> events) {
        lock.lock();
        try {
            int applied = 0;
            int ignored = 0;
            int rejected = 0;
            for (EventView event : events) {
                DeliveryOutcome outcome = applyOne(event);
                switch (outcome) {
                    case APPLIED -> applied++;
                    case IGNORED -> ignored++;
                    case REJECTED -> rejected++;
                }
            }
            return new DeliveryResult(applied, ignored, rejected, lastRevision, snapshotNodes());
        } finally {
            lock.unlock();
        }
    }

    /**
     * 应用单个事件。
     *
     * @return APPLIED=按序应用；IGNORED=重复或旧事件丢弃；REJECTED=跳号乱序拒绝
     */
    public DeliveryOutcome applyOne(EventView event) {
        lock.lock();
        try {
            if (event.revision() <= lastRevision) {
                return DeliveryOutcome.IGNORED;
            }
            if (event.revision() != lastRevision + 1) {
                return DeliveryOutcome.REJECTED;
            }
            mutate(event);
            lastRevision = event.revision();
            return DeliveryOutcome.APPLIED;
        } finally {
            lock.unlock();
        }
    }

    private void mutate(EventView event) {
        switch (event.eventType()) {
            case EventTypes.RESOURCE_DECLARED -> {
                ResourceView view = new ResourceView(event.resourceCategory(),
                        event.fullyQualifiedName(), event.interfaceType());
                resourcesByNode.computeIfAbsent(event.nodeId(), k -> new ConcurrentHashMap<>())
                        .put(resourceKey(view), view);
            }
            case EventTypes.RESOURCE_RELEASED -> {
                Map<String, ResourceView> held = resourcesByNode.get(event.nodeId());
                if (held != null && event.resourceCategory() != null) {
                    ResourceView view = new ResourceView(event.resourceCategory(),
                            event.fullyQualifiedName(), event.interfaceType());
                    held.remove(resourceKey(view));
                }
            }
            case EventTypes.NODE_CRASH_CLEANUP ->
                // 崩溃清理事件释放该节点全部资源，与注册中心事务清理结果一致
                    resourcesByNode.remove(event.nodeId());
            default -> {
                // LIFECYCLE_TRANSITION 等节点级事件不改变资源视图
            }
        }
    }

    /** 当前视图快照：所有节点仍持有的资源，按 nodeId 与资源键排序。 */
    public ProjectionSnapshot snapshot() {
        lock.lock();
        try {
            return new ProjectionSnapshot(lastRevision, snapshotNodes());
        } finally {
            lock.unlock();
        }
    }

    private List<NodeResources> snapshotNodes() {
        List<NodeResources> result = new ArrayList<>();
        resourcesByNode.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    List<ResourceView> views = entry.getValue().values().stream()
                            .sorted(Comparator.comparing(ResourceView::resourceCategory)
                                    .thenComparing(ResourceView::fullyQualifiedName)
                                    .thenComparing(ResourceView::interfaceType))
                            .toList();
                    result.add(new NodeResources(entry.getKey(), views));
                });
        return result;
    }

    /** 重置订阅方视图（测试或重新订阅时使用）。 */
    public void reset() {
        lock.lock();
        try {
            lastRevision = 0;
            resourcesByNode.clear();
        } finally {
            lock.unlock();
        }
    }

    public long getLastRevision() {
        lock.lock();
        try {
            return lastRevision;
        } finally {
            lock.unlock();
        }
    }

    private static String resourceKey(ResourceView view) {
        return Objects.requireNonNullElse(view.resourceCategory(), "") + "|"
                + Objects.requireNonNullElse(view.fullyQualifiedName(), "") + "|"
                + Objects.requireNonNullElse(view.interfaceType(), "");
    }

    /** 单事件投递结论。 */
    public enum DeliveryOutcome {
        APPLIED, IGNORED, REJECTED
    }

    /**
     * 批量投递结果。
     *
     * @param applied      按序应用的事件数
     * @param ignored      重复/旧事件忽略数
     * @param rejected     跳号乱序拒绝数
     * @param lastRevision 投递完成后本地水位
     * @param nodes        投递完成后的本地资源视图
     */
    public record DeliveryResult(int applied, int ignored, int rejected,
                                 long lastRevision, List<NodeResources> nodes) {
    }

    /**
     * 单节点在订阅方视图中的资源。
     */
    public record NodeResources(String nodeId, List<ResourceView> resources) {
    }

    /**
     * 订阅方视图快照。
     */
    public record ProjectionSnapshot(long lastRevision, List<NodeResources> nodes) {
    }
}
