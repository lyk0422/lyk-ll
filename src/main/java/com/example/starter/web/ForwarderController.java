package com.example.starter.web;

import com.example.starter.domain.RetentionPolicy;
import com.example.starter.dto.AckRequest;
import com.example.starter.dto.AckResponse;
import com.example.starter.dto.IngestResponse;
import com.example.starter.dto.MessageRequest;
import com.example.starter.dto.NetworkRequest;
import com.example.starter.dto.StatusResponse;
import com.example.starter.dto.TopicPolicyRequest;
import com.example.starter.service.ForwarderService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 边缘转发器 HTTP 入口。
 */
@RestController
@RequestMapping("/api/forwarder")
public class ForwarderController {

    private final ForwarderService service;

    public ForwarderController(ForwarderService service) {
        this.service = service;
    }

    /** 配置 Topic 策略。 */
    @PostMapping("/topics")
    public java.util.Map<String, String> configureTopic(@Valid @RequestBody TopicPolicyRequest request) {
        RetentionPolicy policy = RetentionPolicy.parse(request.policy());
        service.configureTopic(request.topic(), policy);
        return java.util.Map.of("topic", request.topic(), "policy", policy.format());
    }

    /** 设备写入消息（离线缓存或直接进入补发流程）。 */
    @PostMapping("/messages")
    public IngestResponse ingest(@Valid @RequestBody MessageRequest request) {
        return service.ingest(request.deviceId(), request.topic(), request.seq(),
                request.priority(), request.dedupKey(), request.payload());
    }

    /** 上游 ACK 上报，允许重复、乱序。 */
    @PostMapping("/acks")
    public AckResponse ack(@Valid @RequestBody AckRequest request) {
        return service.acknowledge(request.dedupKey());
    }

    /** 手动触发一次补发（调度器也会周期触发）。 */
    @PostMapping("/flush")
    public java.util.Map<String, Integer> flush() {
        return java.util.Map.of("sent", service.flush());
    }

    /** 模拟网络通断。 */
    @PostMapping("/network")
    public java.util.Map<String, Boolean> network(@Valid @RequestBody NetworkRequest request) {
        service.setOnline(request.online());
        return java.util.Map.of("online", request.online());
    }

    /** 队列、in-flight 与计数快照。 */
    @GetMapping("/status")
    public StatusResponse status() {
        return service.status();
    }
}
