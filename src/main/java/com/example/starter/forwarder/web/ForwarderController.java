package com.example.starter.forwarder.web;

import com.example.starter.forwarder.service.ForwarderService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 设备消息写入与上游 ACK 接口。
 */
@RestController
@RequestMapping("/api/forwarder")
public class ForwarderController {

    private final ForwarderService forwarderService;

    public ForwarderController(ForwarderService forwarderService) {
        this.forwarderService = forwarderService;
    }

    /** 合成设备写入一条消息；离线时按 Topic 策略缓存或丢弃。 */
    @PostMapping("/messages")
    public IngestResponse ingest(@Valid @RequestBody IngestRequest request) {
        return forwarderService.ingest(request);
    }

    /** 上游 ACK；允许重复、乱序到达，仅精确确认 in-flight 记录。 */
    @PostMapping("/acks")
    public AckResponse ack(@Valid @RequestBody AckRequest request) {
        return forwarderService.acknowledge(request);
    }
}
