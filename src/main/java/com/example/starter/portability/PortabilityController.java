package com.example.starter.portability;

import java.util.List;

import com.example.starter.portability.dto.ApplyRequest;
import com.example.starter.portability.dto.EventResponse;
import com.example.starter.portability.dto.FlowResponse;
import com.example.starter.portability.dto.RequestIdRequest;
import com.example.starter.portability.dto.StoredResponse;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 携号转网流转接口：申请、签码、携出方确认、生效广播、撤销与事件查询。
 * 写接口均以 requestId 幂等，响应体为首次处理结果的 JSON 原文。
 */
@RestController
@RequestMapping("/api/portability")
public class PortabilityController {

    private final PortabilityService service;

    public PortabilityController(PortabilityService service) {
        this.service = service;
    }

    /**
     * 提交携号转网申请并进行资格校验。
     */
    @PostMapping("/applications")
    public ResponseEntity<String> apply(@Valid @RequestBody ApplyRequest request) {
        return toEntity(service.apply(request));
    }

    /**
     * 签发授权码（自签发起 24 小时有效，UTC）。
     */
    @PostMapping("/flows/{flowId}/auth-code")
    public ResponseEntity<String> issueAuthCode(@PathVariable long flowId,
                                                @Valid @RequestBody RequestIdRequest request) {
        return toEntity(service.issueAuthCode(flowId, request));
    }

    /**
     * 携出方确认。
     */
    @PostMapping("/flows/{flowId}/donor-confirm")
    public ResponseEntity<String> donorConfirm(@PathVariable long flowId,
                                               @Valid @RequestBody RequestIdRequest request) {
        return toEntity(service.donorConfirm(flowId, request));
    }

    /**
     * 生效广播：仅在每日 UTC 02:00-04:00 窗口内生效。
     */
    @PostMapping("/flows/{flowId}/broadcast")
    public ResponseEntity<String> broadcast(@PathVariable long flowId,
                                            @Valid @RequestBody RequestIdRequest request) {
        return toEntity(service.broadcast(flowId, request));
    }

    /**
     * 用户在生效前撤销流程。
     */
    @PostMapping("/flows/{flowId}/cancel")
    public ResponseEntity<String> cancel(@PathVariable long flowId,
                                         @Valid @RequestBody RequestIdRequest request) {
        return toEntity(service.cancel(flowId, request));
    }

    /**
     * 查询流程当前状态。
     */
    @GetMapping("/flows/{flowId}")
    public FlowResponse getFlow(@PathVariable long flowId) {
        return service.getFlow(flowId);
    }

    /**
     * 按发生顺序查询流程事件。
     */
    @GetMapping("/flows/{flowId}/events")
    public List<EventResponse> getEvents(@PathVariable long flowId) {
        return service.getEvents(flowId);
    }

    private ResponseEntity<String> toEntity(StoredResponse response) {
        return ResponseEntity.status(response.httpStatus())
                .contentType(MediaType.APPLICATION_JSON)
                .body(response.body());
    }
}
