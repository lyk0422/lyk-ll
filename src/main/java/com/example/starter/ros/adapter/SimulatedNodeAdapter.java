package com.example.starter.ros.adapter;

import com.example.starter.ros.NodeRejectedException;
import com.example.starter.ros.NodeTimeoutException;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存模拟节点适配器：按节点 ID 配置拒绝理由/响应延迟，供本地运行与测试注入。
 *
 * <p>{@link #validate} 只做校验与暂存，不改变“已应用值”；只有 {@link #apply} 被调用后
 * {@link #appliedValues} 才会变化，因此回滚后可直接读回断言节点侧未被污染。
 * 超时既可由本适配器在延迟超过时限时自行抛出，也可由事务服务的有界等待兜底。
 */
@Component
public class SimulatedNodeAdapter implements NodeAdapter {

    private enum Mode { ACCEPT, REJECT }

    private record Behavior(Mode mode, String reason, long delayMillis) {
        static Behavior accept() {
            return new Behavior(Mode.ACCEPT, null, 0L);
        }
    }

    private final Map<String, Behavior> behaviors = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> applied = new ConcurrentHashMap<>();

    /** 配置节点始终接受（默认行为）。 */
    public void accept(String nodeId) {
        behaviors.put(nodeId, Behavior.accept());
    }

    /** 配置节点拒绝候选配置并给出理由。 */
    public void reject(String nodeId, String reason) {
        behaviors.put(nodeId, new Behavior(Mode.REJECT, reason, 0L));
    }

    /** 配置节点在响应前延迟（用于触发配置时限超时）。 */
    public void delay(String nodeId, long delayMillis) {
        Behavior old = behaviors.getOrDefault(nodeId, Behavior.accept());
        behaviors.put(nodeId, new Behavior(old.mode(), old.reason(), delayMillis));
    }

    /** 清空全部行为与已应用值（测试场景间复位用）。 */
    public void resetAll() {
        behaviors.clear();
        applied.clear();
    }

    @Override
    public void validate(String nodeId, Map<String, Object> candidate, long timeoutMillis)
            throws NodeRejectedException, NodeTimeoutException {
        Behavior behavior = behaviors.getOrDefault(nodeId, Behavior.accept());
        if (behavior.delayMillis() > 0) {
            // 真实等待，由事务服务的有界 Future.get 在超时后中断本线程
            try {
                Thread.sleep(behavior.delayMillis());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new NodeTimeoutException("node '" + nodeId + "' did not respond within "
                        + timeoutMillis + "ms");
            }
        }
        if (behavior.mode() == Mode.REJECT) {
            throw new NodeRejectedException("node '" + nodeId + "' rejected candidate: "
                    + behavior.reason());
        }
    }

    @Override
    public void apply(String nodeId, Map<String, Object> candidate, long newVersion) {
        applied.put(nodeId, Map.copyOf(candidate));
    }

    @Override
    public Map<String, Object> appliedValues(String nodeId) {
        return applied.getOrDefault(nodeId, Map.of());
    }
}
