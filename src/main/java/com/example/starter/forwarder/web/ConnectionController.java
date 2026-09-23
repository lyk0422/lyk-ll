package com.example.starter.forwarder.web;

import com.example.starter.forwarder.repo.MessageRepository;
import com.example.starter.forwarder.service.ForwarderService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 连接状态与缓存观测接口：模拟断网/恢复并查看当前缓存占用。
 */
@RestController
@RequestMapping("/api/forwarder/connection")
public class ConnectionController {

    private final ForwarderService forwarderService;
    private final MessageRepository messageRepository;

    public ConnectionController(ForwarderService forwarderService, MessageRepository messageRepository) {
        this.forwarderService = forwarderService;
        this.messageRepository = messageRepository;
    }

    public record ConnectionStatus(boolean online, int activeMessages) {
    }

    @GetMapping
    public ConnectionStatus status() {
        return new ConnectionStatus(forwarderService.isOnline(), messageRepository.activeCount());
    }

    /** 模拟网络恢复（online=true）或断网（online=false）。 */
    @PostMapping
    public ConnectionStatus setOnline(@RequestParam boolean online) {
        forwarderService.setOnline(online);
        return new ConnectionStatus(forwarderService.isOnline(), messageRepository.activeCount());
    }
}
