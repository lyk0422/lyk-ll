package com.example.starter.ros.web;

import com.example.starter.ros.service.EventSubscriptionService;
import com.example.starter.ros.service.NodeRegistryService;
import com.example.starter.ros.web.dto.CheckpointResponse;
import com.example.starter.ros.web.dto.EventBatchResponse;
import com.example.starter.ros.web.dto.NodeOperationRequest;
import com.example.starter.ros.web.dto.NodeResponse;
import com.example.starter.ros.web.dto.RecoverRequest;
import com.example.starter.ros.web.dto.RegisterNodeRequest;
import com.example.starter.ros.web.dto.TransitionRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * ROS 2 风格节点注册中心接口。
 */
@RestController
@RequestMapping("/api/ros")
public class NodeRegistryController {

    private final NodeRegistryService registryService;
    private final EventSubscriptionService eventService;

    public NodeRegistryController(NodeRegistryService registryService,
                                  EventSubscriptionService eventService) {
        this.registryService = registryService;
        this.eventService = eventService;
    }

    @PostMapping("/nodes")
    @ResponseStatus(HttpStatus.CREATED)
    public NodeResponse register(@Valid @RequestBody RegisterNodeRequest request) {
        return registryService.register(request);
    }

    @PostMapping("/nodes/deregister")
    public NodeResponse deregister(@Valid @RequestBody NodeOperationRequest request) {
        return registryService.deregister(request);
    }

    @PostMapping("/nodes/transition")
    public NodeResponse transition(@Valid @RequestBody TransitionRequest request) {
        return registryService.transition(request);
    }

    @PostMapping("/nodes/crash")
    public NodeResponse crash(@Valid @RequestBody NodeOperationRequest request) {
        return registryService.crash(request);
    }

    @PostMapping("/nodes/recover")
    public NodeResponse recover(@Valid @RequestBody RecoverRequest request) {
        return registryService.recover(request);
    }

    @GetMapping("/nodes/{nodeId}")
    public NodeResponse getNode(@PathVariable String nodeId) {
        return registryService.getNode(nodeId);
    }

    @GetMapping("/nodes")
    public List<NodeResponse> listNodes() {
        return registryService.listNodes();
    }

    @PostMapping("/nodes/{nodeId}/checkpoint")
    public CheckpointResponse saveCheckpoint(@PathVariable String nodeId) {
        return registryService.saveCheckpoint(nodeId);
    }

    @GetMapping("/events")
    public EventBatchResponse poll(@RequestParam String subscriberId,
                                   @RequestParam(defaultValue = "50") int limit) {
        return eventService.poll(subscriberId, limit);
    }

    @PostMapping("/events/{subscriberId}/ack")
    public long acknowledge(@PathVariable String subscriberId, @RequestParam long revision) {
        return eventService.acknowledge(subscriberId, revision);
    }
}
