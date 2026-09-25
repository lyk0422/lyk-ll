package com.example.starter.portability.web;

import java.util.List;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.portability.service.PortabilityService;

/**
 * 携号转网流转 API：申请、携出方确认、生效广播、撤销、流程与事件查询。
 */
@RestController
@RequestMapping("/api/portability/requests")
public class PortabilityController {

    private final PortabilityService service;

    public PortabilityController(PortabilityService service) {
        this.service = service;
    }

    /**
     * 申请携转：资格校验通过后签发授权码并建立在途流程；按 requestId 幂等。
     */
    @PostMapping
    public ResponseEntity<FlowView> apply(@Valid @RequestBody ApplyRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.apply(request));
    }

    /**
     * 携出方确认：校验授权码有效后将流程置为待生效。
     */
    @PostMapping("/{flowId}/confirm")
    public FlowView confirm(@PathVariable long flowId, @Valid @RequestBody ConfirmRequest request) {
        return service.confirm(flowId, request);
    }

    /**
     * 生效广播：仅在每日 02:00-04:00 窗口内生效；按 requestId 幂等。
     */
    @PostMapping("/{flowId}/broadcast")
    public BroadcastView broadcast(@PathVariable long flowId, @Valid @RequestBody BroadcastRequest request) {
        return service.broadcast(flowId, request);
    }

    /**
     * 用户在生效前撤销流程，撤销为终态。
     */
    @PostMapping("/{flowId}/cancel")
    public FlowView cancel(@PathVariable long flowId) {
        return service.cancel(flowId);
    }

    @GetMapping("/{flowId}")
    public FlowView getFlow(@PathVariable long flowId) {
        return service.getFlow(flowId);
    }

    /**
     * 按序查询流程各阶段事件。
     */
    @GetMapping("/{flowId}/events")
    public List<EventView> listEvents(@PathVariable long flowId) {
        return service.listEvents(flowId);
    }
}
