package com.example.starter.ros.web;

import com.example.starter.ros.node.NodeBehavior;
import com.example.starter.ros.node.SimulatedNodeAdapter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 模拟节点行为管理（本地演示与测试入口，非真实 ROS）：
 * 可将节点置为 ACCEPT/REJECT/TIMEOUT，用于复现拒绝与超时全量回滚；
 * 并可查询节点已应用值以验证回滚后未被改变。
 */
@RestController
@RequestMapping("/api/ros/simulation")
public class SimulationController {

    private final SimulatedNodeAdapter nodeAdapter;

    public SimulationController(SimulatedNodeAdapter nodeAdapter) {
        this.nodeAdapter = nodeAdapter;
    }

    /** 配置节点行为：{"mode":"TIMEOUT","reason":"...","validateDelayMs":0}。 */
    @PostMapping("/nodes/{node}/behavior")
    public Map<String, Object> configure(@PathVariable String node,
                                         @Valid @RequestBody BehaviorRequest request) {
        NodeBehavior.Mode mode = NodeBehavior.Mode.valueOf(request.mode().trim().toUpperCase());
        nodeAdapter.configure(node, new NodeBehavior(mode, request.reason(),
                request.validateDelayMs() == null ? 0L : request.validateDelayMs()));
        return Map.of("node", node, "mode", mode.name());
    }

    @PostMapping("/reset")
    public Map<String, Object> reset() {
        nodeAdapter.reset();
        return Map.of("reset", true);
    }

    /** 查询节点已应用值；回滚场景应保持为上一次成功提交的内容。 */
    @GetMapping("/nodes/{node}/applied")
    public Map<String, Object> applied(@PathVariable String node) {
        return nodeAdapter.getApplied(node);
    }

    /** 模拟行为配置请求体。 */
    public record BehaviorRequest(@NotBlank String mode, String reason, Long validateDelayMs) {
    }
}
