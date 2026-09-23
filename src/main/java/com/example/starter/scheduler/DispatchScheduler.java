package com.example.starter.scheduler;

import com.example.starter.service.ForwarderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 补发调度器：定时扫描待发消息并在链路恢复后补发，超时 in-flight 由服务在每轮回收。
 */
@Component
public class DispatchScheduler {

    private static final Logger log = LoggerFactory.getLogger(DispatchScheduler.class);

    private final ForwarderService forwarderService;

    public DispatchScheduler(ForwarderService forwarderService) {
        this.forwarderService = forwarderService;
    }

    @Scheduled(fixedDelayString = "${forwarder.dispatch-interval-ms:500}")
    public void tick() {
        try {
            int delivered = forwarderService.dispatchOnce();
            if (delivered > 0) {
                log.info("本轮补发成功投递 {} 条消息", delivered);
            }
        } catch (RuntimeException ex) {
            // 单轮失败不影响后续扫描，消息仍持久化在本地。
            log.warn("补发调度本轮执行失败", ex);
        }
    }
}
