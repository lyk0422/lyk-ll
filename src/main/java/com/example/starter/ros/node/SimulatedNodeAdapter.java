package com.example.starter.ros.node;

import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于内存行为表的节点适配器：不连接真实 ROS，
 * 通过行为配置模拟接受、拒绝、超时，并记录各节点已应用值用于回滚断言。
 */
@Component
public class SimulatedNodeAdapter implements NodeAdapter {

    private final Map<String, NodeBehavior> behaviors = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> applied = new ConcurrentHashMap<>();

    /** 配置某节点的模拟行为；未配置的节点默认 ACCEPT。 */
    public void configure(String node, NodeBehavior behavior) {
        behaviors.put(node, behavior);
    }

    /** 清除全部行为配置与已应用记录（测试场景间重置）。 */
    public void reset() {
        behaviors.clear();
        applied.clear();
    }

    /** 返回节点当前已应用配置的只读快照（节点已应用值）。 */
    public Map<String, Object> getApplied(String node) {
        return applied.getOrDefault(node, Collections.emptyMap());
    }

    @Override
    public ValidationOutcome validate(String node, Map<String, Object> candidate, long timeoutMs) {
        NodeBehavior behavior = behaviors.getOrDefault(node, NodeBehavior.accept());
        long delay = Math.max(0L, behavior.validateDelayMs());
        if (behavior.mode() == NodeBehavior.Mode.TIMEOUT) {
            // 模拟节点在时限内无响应：睡到超过时限后仍不返回有效结论
            sleepQuietly(timeoutMs + Math.max(delay, 50L));
            return ValidationOutcome.reject("节点模拟超时无响应");
        }
        if (delay > 0) {
            sleepQuietly(delay);
        }
        if (behavior.mode() == NodeBehavior.Mode.REJECT) {
            return ValidationOutcome.reject(behavior.reason());
        }
        return ValidationOutcome.accept();
    }

    @Override
    public void apply(String node, Map<String, Object> appliedConfig) {
        applied.put(node, Map.copyOf(appliedConfig));
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待节点响应时被中断", e);
        }
    }
}
