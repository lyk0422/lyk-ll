package com.example.starter.cosmetic.support;

import com.example.starter.cosmetic.service.FilingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 公示期满自动生效定时器：按固定延迟扫描已到第 10 日 24:00(UTC) 的公示件并原子生效。
 * 测试可通过 cosmetic.public-notice.scheduling-enabled=false 关闭，避免后台扫描干扰竞态断言。
 */
@Component
@ConditionalOnProperty(name = "cosmetic.public-notice.scheduling-enabled", havingValue = "true",
        matchIfMissing = true)
public class PublicNoticeScheduler {

    private static final Logger log = LoggerFactory.getLogger(PublicNoticeScheduler.class);

    private final FilingService filingService;

    public PublicNoticeScheduler(FilingService filingService) {
        this.filingService = filingService;
    }

    /** 每 30 秒扫描一次；到期判定使用 &lt;=，恰期满当时即生效。 */
    @Scheduled(fixedDelayString = "${cosmetic.public-notice.scan-delay-ms:30000}")
    public void scan() {
        int effected = filingService.expireDuePublicNotices(null);
        if (effected > 0) {
            log.info("公示期满自动生效备案数量: {}", effected);
        }
    }
}
