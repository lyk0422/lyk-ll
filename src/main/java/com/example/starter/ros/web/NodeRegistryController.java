package com.example.starter.ros.web;

import com.example.starter.ros.api.EventView;
import com.example.starter.ros.api.NodeOperationRequest;
import com.example.starter.ros.api.NodeView;
import com.example.starter.ros.api.RegisterNodeRequest;
import com.example.starter.ros.api.RestoreCheckpointRequest;
import com.example.starter.ros.api.RestoreResult;
import com.example.starter.ros.api.SaveCheckpointRequest;
import com.example.starter.ros.api.TransitionRequest;
import com.example.starter.ros.service.NodeRegistryService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * ROS 2 风格节点注册中心 HTTP 入口（不接入任何真实 ROS 中间件）。
 */
@RestController
@RequestMapping("/api/ros")
public class NodeRegistryController {

    private final NodeRegistryService service;

    public NodeRegistryController(NodeRegistryService service) {
        this.service = service;
    }

    /** 注册节点并声明资源。 */
    @PostMapping("/nodes")
    @ResponseStatus(HttpStatus.CREATED)
    public NodeView register(@Valid @RequestBody RegisterNodeRequest request) {
        return service.register(request);
    }

    /** 查询节点。 */
    @GetMapping("/nodes/{nodeId}")
    public NodeView getNode(@PathVariable String nodeId) {
        return service.getNode(nodeId);
    }

    /** 列出全部节点。 */
    @GetMapping("/nodes")
    public List<NodeView> listNodes() {
        return service.listNodes();
    }

    /** 注销节点并释放全部资源。 */
    @PostMapping("/nodes/{nodeId}/deregister")
    public NodeView deregister(@PathVariable String nodeId,
                               @Valid @RequestBody NodeOperationRequest request) {
        return service.deregister(nodeId, request.requestId());
    }

    /** 生命周期相邻迁移。 */
    @PostMapping("/nodes/{nodeId}/transition")
    public NodeView transition(@PathVariable String nodeId,
                               @Valid @RequestBody TransitionRequest request) {
        return service.transition(nodeId, request);
    }

    /** 模拟节点异常退出（业务 API）。 */
    @PostMapping("/nodes/{nodeId}/crash")
    public NodeView crash(@PathVariable String nodeId,
                          @Valid @RequestBody NodeOperationRequest request) {
        return service.crash(nodeId, request.requestId());
    }

    /** 按递增 revision 订阅资源变更事件。 */
    @GetMapping("/events")
    public List<EventView> events(@RequestParam(defaultValue = "0") long after,
                                  @RequestParam(defaultValue = "100") int limit) {
        return service.eventsAfter(after, Math.min(Math.max(limit, 1), 1000));
    }

    /** 保存检查点。 */
    @PostMapping("/checkpoints")
    @ResponseStatus(HttpStatus.CREATED)
    public void saveCheckpoint(@Valid @RequestBody SaveCheckpointRequest request) {
        service.saveCheckpoint(request.checkpointId(), request.requestId());
    }

    /** 在当前 JVM 内从检查点恢复。 */
    @PostMapping("/checkpoints/restore")
    public RestoreResult restoreCheckpoint(@Valid @RequestBody RestoreCheckpointRequest request) {
        return service.restoreCheckpoint(request.checkpointId(), request.requestId());
    }
}
