package com.example.starter.forwarder.web;

import java.util.List;

import com.example.starter.forwarder.AckOutcome;
import com.example.starter.forwarder.AuditRepository;
import com.example.starter.forwarder.AuditRepository.AuditRow;
import com.example.starter.forwarder.ForwarderService;
import com.example.starter.forwarder.IncomingMessage;
import com.example.starter.forwarder.IngestOutcome;
import com.example.starter.forwarder.MessageRepository;
import com.example.starter.forwarder.MessageView;
import com.example.starter.forwarder.NetworkState;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 转发器本地 HTTP 入口：设备消息写入、ACK、网络开关与状态查询。
 */
@RestController
@RequestMapping("/api/forwarder")
public class ForwarderController {

    private final ForwarderService service;
    private final MessageRepository messages;
    private final AuditRepository audit;
    private final NetworkState network;

    public ForwarderController(ForwarderService service, MessageRepository messages,
                               AuditRepository audit, NetworkState network) {
        this.service = service;
        this.messages = messages;
        this.audit = audit;
        this.network = network;
    }

    /**
     * 设备消息写入请求。
     */
    public record MessageRequest(
            @NotBlank String deviceId,
            @NotBlank String topic,
            @NotNull Long deviceSeq,
            @NotNull Integer priority,
            @NotBlank String dedupKey,
            String payload) {
    }

    /**
     * ACK 请求：dedupKey 或 deviceId+deviceSeq 二选一。
     */
    public record AckRequest(String dedupKey, String deviceId, Long deviceSeq) {
    }

    /**
     * 网络开关请求。
     */
    public record NetworkRequest(@NotNull Boolean online) {
    }

    public record IngestResponse(String outcome) {
    }

    public record AckResponse(String outcome) {
    }

    public record NetworkResponse(boolean online, int drained) {
    }

    /**
     * 设备离线/在线写入一条消息。
     */
    @PostMapping("/messages")
    public ResponseEntity<IngestResponse> ingest(@Valid @RequestBody MessageRequest request) {
        IngestOutcome outcome = service.ingest(new IncomingMessage(
                request.deviceId(), request.topic(), request.deviceSeq(),
                request.priority(), request.dedupKey(), request.payload()));
        if (network.isOnline() && outcome == IngestOutcome.QUEUED) {
            service.drain();
        }
        return switch (outcome) {
            case QUEUED, DUPLICATE -> ResponseEntity.ok(new IngestResponse(outcome.name()));
            case DROPPED_BY_POLICY -> ResponseEntity.unprocessableEntity()
                    .body(new IngestResponse(outcome.name()));
            case REJECTED_CAPACITY -> ResponseEntity.status(507)
                    .body(new IngestResponse(outcome.name()));
        };
    }

    /**
     * 上游 ACK 回传（可重复、可乱序）。
     */
    @PostMapping("/acks")
    public ResponseEntity<AckResponse> ack(@RequestBody AckRequest request) {
        AckOutcome outcome;
        if (request.dedupKey() != null && !request.dedupKey().isBlank()) {
            outcome = service.ack(request.dedupKey());
        } else if (request.deviceId() != null && request.deviceSeq() != null) {
            outcome = service.ackByDeviceSeq(request.deviceId(), request.deviceSeq());
        } else {
            return ResponseEntity.badRequest().body(new AckResponse("INVALID_REQUEST"));
        }
        return outcome == AckOutcome.NOT_FOUND
                ? ResponseEntity.status(404).body(new AckResponse(outcome.name()))
                : ResponseEntity.ok(new AckResponse(outcome.name()));
    }

    /**
     * 切换网络状态；恢复在线时立即触发一轮补发。
     */
    @PostMapping("/network")
    public NetworkResponse network(@Valid @RequestBody NetworkRequest request) {
        network.setOnline(request.online());
        int drained = request.online() ? service.drain() : 0;
        return new NetworkResponse(network.isOnline(), drained);
    }

    /**
     * 查询全部消息（含状态）。
     */
    @GetMapping("/messages")
    public List<MessageView> messages() {
        return messages.findAll();
    }

    /**
     * 查询审计记录。
     */
    @GetMapping("/audit")
    public List<AuditRow> audit() {
        return audit.findAll();
    }
}
