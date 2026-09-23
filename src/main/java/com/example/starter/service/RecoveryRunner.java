package com.example.starter.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 启动恢复：服务（重）启动后把上次进程遗留的 in-flight 消息重置为待发并补发。
 * 依赖 H2 文件库的持久化状态实现跨进程恢复。
 */
@Component
@Order(0)
public class RecoveryRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RecoveryRunner.class);

    private final ForwarderServiceImpl service;

    public RecoveryRunner(ForwarderServiceImpl service) {
        this.service = service;
    }

    @Override
    public void run(ApplicationArguments args) {
        int flushed = service.recoverAfterRestart();
        log.info("Startup recovery completed, redispatched {} message(s)", flushed);
    }
}
