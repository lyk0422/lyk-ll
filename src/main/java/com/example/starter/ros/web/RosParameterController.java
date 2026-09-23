package com.example.starter.ros.web;

import com.example.starter.ros.EventRecord;
import com.example.starter.ros.EventSubscriptionService;
import com.example.starter.ros.ParameterQueryService;
import com.example.starter.ros.PendingRebootRequest;
import com.example.starter.ros.PendingRebootService;
import com.example.starter.ros.TransactionRequest;
import com.example.starter.ros.TransactionResponse;
import com.example.starter.ros.TransactionService;
import com.example.starter.ros.adapter.SimulatedNodeAdapter;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * ROS 参数风格配置中心 HTTP 入口。
 */
@RestController
@RequestMapping("/api/ros")
public class RosParameterController {

    private final ParameterQueryService parameterQueryService;
    private final TransactionService transactionService;
    private final EventSubscriptionService eventSubscriptionService;
    private final PendingRebootService pendingRebootService;
    private final SimulatedNodeAdapter nodeAdapter;

    public RosParameterController(ParameterQueryService parameterQueryService,
                                  TransactionService transactionService,
                                  EventSubscriptionService eventSubscriptionService,
                                  PendingRebootService pendingRebootService,
                                  SimulatedNodeAdapter nodeAdapter) {
        this.parameterQueryService = parameterQueryService;
        this.transactionService = transactionService;
        this.eventSubscriptionService = eventSubscriptionService;
        this.pendingRebootService = pendingRebootService;
        this.nodeAdapter = nodeAdapter;
    }

    /** 读取节点生效参数（默认值合并已提交值；不含待重启配置）。 */
    @GetMapping("/nodes/{nodeId}/parameters")
    public Map<String, Object> getParameters(@PathVariable String nodeId) {
        return parameterQueryService.effectiveValues(nodeId);
    }

    /** 事务式热更新：全部目标节点接受后才原子提交版本与事务事件。 */
    @PostMapping("/transactions")
    public TransactionResponse update(@Valid @RequestBody TransactionRequest request) {
        return transactionService.execute(request);
    }

    /** 为静态参数登记待重启配置（不改变已生效值，applied 恒为 false）。 */
    @PostMapping("/nodes/{nodeId}/pending-reboot")
    public PendingRebootRequest.Response registerPendingReboot(
            @PathVariable String nodeId,
            @Valid @RequestBody PendingRebootRequest request) {
        return pendingRebootService.register(nodeId, request);
    }

    /** 订阅者拉取事件（严格大于其已 ACK 游标，断线重连不重复）。 */
    @GetMapping("/subscriptions/{subscriberId}/events")
    public List<EventRecord> pull(@PathVariable String subscriberId,
                                  @RequestParam(defaultValue = "100") int limit) {
        return eventSubscriptionService.pull(subscriberId, limit);
    }

    /** 查询订阅者当前 ACK 游标。 */
    @GetMapping("/subscriptions/{subscriberId}")
    public Map<String, Object> subscription(@PathVariable String subscriberId) {
        return Map.of("subscriberId", subscriberId,
                "ackCursor", eventSubscriptionService.ackCursor(subscriberId));
    }

    /** 确认事件游标（只允许向前推进）。 */
    @PostMapping("/subscriptions/{subscriberId}/ack")
    public Map<String, Object> ack(@PathVariable String subscriberId,
                                   @RequestParam long cursor) {
        eventSubscriptionService.ack(subscriberId, cursor);
        return Map.of("subscriberId", subscriberId,
                "ackCursor", eventSubscriptionService.ackCursor(subscriberId));
    }

    /**
     * 配置模拟节点行为（本地无真实 ROS 时使用）：
     * {@code {"mode":"ACCEPT|REJECT|DELAY","reason":"...","delayMillis":3000}}。
     */
    @PostMapping("/nodes/{nodeId}/simulation")
    public Map<String, Object> configureSimulation(@PathVariable String nodeId,
                                                   @RequestBody SimulationConfig config) {
        switch (config.mode() == null ? "ACCEPT" : config.mode().toUpperCase()) {
            case "REJECT" -> nodeAdapter.reject(nodeId,
                    config.reason() == null ? "simulated rejection" : config.reason());
            case "DELAY" -> {
                nodeAdapter.accept(nodeId);
                nodeAdapter.delay(nodeId, config.delayMillis() == null ? 0L : config.delayMillis());
            }
            default -> nodeAdapter.accept(nodeId);
        }
        return Map.of("nodeId", nodeId, "configured", config);
    }

    /** 模拟节点行为配置。 */
    public record SimulationConfig(String mode, String reason, Long delayMillis) {
    }
}
