package com.example.starter.controller;

import com.example.starter.adapter.SyntheticUpstreamAdapter;
import com.example.starter.dto.AckRequest;
import com.example.starter.dto.IngestResult;
import com.example.starter.dto.MessageRequest;
import com.example.starter.dto.TopicPolicyRequest;
import com.example.starter.model.AuditRecord;
import com.example.starter.model.DeviceMessage;
import com.example.starter.model.TopicPolicy;
import com.example.starter.repository.AuditRepository;
import com.example.starter.service.ForwarderService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 边缘转发器 REST 入口。
 */
@RestController
@RequestMapping("/api")
public class ForwarderController {

    private final ForwarderService forwarderService;
    private final SyntheticUpstreamAdapter upstreamAdapter;
    private final AuditRepository auditRepository;

    public ForwarderController(ForwarderService forwarderService,
                               SyntheticUpstreamAdapter upstreamAdapter,
                               AuditRepository auditRepository) {
        this.forwarderService = forwarderService;
        this.upstreamAdapter = upstreamAdapter;
        this.auditRepository = auditRepository;
    }

    /** 设备上报消息。 */
    @PostMapping("/messages")
    public Map<String, Object> ingest(@Valid @RequestBody MessageRequest request) {
        IngestResult result = forwarderService.ingest(request);
        DeviceMessage message = result.message();
        return Map.of(
                "outcome", result.outcome(),
                "dedupKey", message.getMessageId(),
                "status", message.getStatus().name());
    }

    /** 配置 Topic 离线保留策略。 */
    @PostMapping("/topics/{topic}/policy")
    public TopicPolicy configure(@PathVariable String topic,
                                 @Valid @RequestBody TopicPolicyRequest request) {
        return forwarderService.configurePolicy(topic, request);
    }

    /** 查询 Topic 策略。 */
    @GetMapping("/topics/{topic}/policy")
    public TopicPolicy policy(@PathVariable String topic) {
        return forwarderService.getPolicy(topic);
    }

    /** 上游 ACK；重复或乱序 ACK 幂等。 */
    @PostMapping("/acks")
    public Map<String, Object> ack(@Valid @RequestBody AckRequest request) {
        boolean confirmed = forwarderService.ack(request.getDedupKey());
        return Map.of("dedupKey", request.getDedupKey(), "confirmed", confirmed);
    }

    /** 切换模拟链路状态（online=true 联网补发，false 断网缓存）。 */
    @PostMapping("/network")
    public Map<String, Object> network(@RequestParam boolean online) {
        upstreamAdapter.setOnline(online);
        return Map.of("online", online);
    }

    /** 手动触发一轮补发。 */
    @PostMapping("/dispatch")
    public Map<String, Object> dispatch() {
        int delivered = forwarderService.dispatchOnce();
        return Map.of("delivered", delivered);
    }

    /** 查询当前待发消息快照（按补发顺序）。 */
    @GetMapping("/messages/pending")
    public List<DeviceMessage> pending() {
        return forwarderService.snapshotPending();
    }

    /** 查询审计记录。 */
    @GetMapping("/audits")
    public List<AuditRecord> audits() {
        return auditRepository.findAll();
    }
}
