package com.example.starter.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 周期性补发调度：在线时按优先级/序号顺序补发待发消息，并回收超时 in-flight。
 */
@Component
public class FlushScheduler {

    private static final Logger log = LoggerFactory.getLogger(FlushScheduler.class);

    private final ForwarderService service;

    public FlushScheduler(ForwarderService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${forwarder.flush-interval-ms:1000}")
    public void tick() {
        try {
            service.flush();
        } catch (Exception e) {
            log.warn("scheduled flush failed: {}", e.getMessage());
        }
    }
}
