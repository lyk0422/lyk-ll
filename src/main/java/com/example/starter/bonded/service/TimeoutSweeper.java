package com.example.starter.bonded.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 超时扫描定时任务：把核注后恰满 48 小时仍未核满的单据自动转异常。
 * 业务操作路径另有惰性检查兜底，二者共用同一事务化扫描逻辑。
 */
@Component
public class TimeoutSweeper {

    private static final Logger log = LoggerFactory.getLogger(TimeoutSweeper.class);

    private final TransferService service;

    public TimeoutSweeper(TransferService service) {
        this.service = service;
    }

    /**
     * 每分钟扫描一次。
     */
    @Scheduled(fixedDelayString = "${bonded.sweep.fixed-delay-ms:60000}",
            initialDelayString = "${bonded.sweep.initial-delay-ms:60000}")
    public void sweep() {
        List<String> marked = service.sweepExpired();
        if (!marked.isEmpty()) {
            log.info("超时转异常单据: {}", marked);
        }
    }
}
