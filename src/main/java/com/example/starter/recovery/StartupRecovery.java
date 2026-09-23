package com.example.starter.recovery;

import com.example.starter.repository.MessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 跨进程恢复：服务启动时将残留 INFLIGHT 消息回退为 PENDING，
 * 因为崩溃可能发生在 ACK 落库之前，这些消息允许重新投递；
 * 若上游实际已收到，重投后由 dedupKey 在补发侧去重，ACK 仍可确认。
 */
@Component
public class StartupRecovery {

    private static final Logger log = LoggerFactory.getLogger(StartupRecovery.class);

    private final MessageRepository messageRepository;

    public StartupRecovery(MessageRepository messageRepository) {
        this.messageRepository = messageRepository;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recover() {
        int recovered = messageRepository.recoverInflightOnStartup();
        if (recovered > 0) {
            log.info("崩溃恢复：{} 条残留 in-flight 消息已回退为待发，将重新补发", recovered);
        }
    }
}
