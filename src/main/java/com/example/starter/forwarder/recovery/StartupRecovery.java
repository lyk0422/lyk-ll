package com.example.starter.forwarder.recovery;

import com.example.starter.forwarder.repo.MessageRepository;
import com.example.starter.forwarder.schedule.DispatchScheduler;
import com.example.starter.forwarder.service.ForwarderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 启动恢复器：服务启动时把上次崩溃遗留、尚未得到明确 ACK 的 in-flight 消息
 * 重置为待发（跨进程恢复），随后放行补发调度器。
 */
@Order(0)
@Component
public class StartupRecovery implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupRecovery.class);

    private final ForwarderService forwarderService;
    private final DispatchScheduler dispatchScheduler;

    public StartupRecovery(ForwarderService forwarderService, DispatchScheduler dispatchScheduler) {
        this.forwarderService = forwarderService;
        this.dispatchScheduler = dispatchScheduler;
    }

    @Override
    public void run(ApplicationArguments args) {
        int reset = forwarderService.recoverInFlightOnStartup();
        log.info("启动恢复：重置遗留 in-flight 消息 {} 条", reset);
        dispatchScheduler.markRecoveryDone();
    }
}
