package com.example.starter.rosconfig.adapter;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 可注入的模拟节点适配器：按节点行为配置接受、拒绝或超时；不依赖真实 ROS。
 * 超时行为通过长时间睡眠模拟，由编排层在配置时限到期后中断等待线程。
 */
@Component
public class SimulatedNodeAdapter implements NodeAdapter {

    private final Map<String, NodeBehavior> behaviors = new ConcurrentHashMap<>();
    private final Map<String, String> rejections = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> applied = new ConcurrentHashMap<>();

    @Override
    public boolean validate(String node, Map<String, Object> candidate) throws InterruptedException {
        NodeBehavior behavior = behaviors.getOrDefault(node, NodeBehavior.ACCEPT);
        switch (behavior) {
            case REJECT:
                rejections.put(node, "node " + node + " rejected candidate config");
                return false;
            case TIMEOUT:
                // 模拟节点在配置时限内无响应；编排层超时后中断该线程
                Thread.sleep(60_000L);
                return true;
            case ACCEPT:
            default:
                return true;
        }
    }

    @Override
    public void apply(String node, Map<String, Object> appliedValues) {
        applied.put(node, Map.copyOf(appliedValues));
    }

    @Override
    public String lastRejection(String node) {
        return rejections.get(node);
    }

    @Override
    public Map<String, Object> appliedView(String node) {
        Map<String, Object> view = applied.get(node);
        return view == null ? Map.of() : view;
    }

    /** 设置节点行为（测试与演示用）。 */
    public void setBehavior(String node, NodeBehavior behavior) {
        behaviors.put(node, behavior);
    }

    /** 重置全部行为与已应用值（测试隔离用）。 */
    public void reset() {
        behaviors.clear();
        rejections.clear();
        applied.clear();
    }
}
