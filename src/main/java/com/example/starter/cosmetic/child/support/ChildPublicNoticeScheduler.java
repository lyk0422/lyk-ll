package com.example.starter.cosmetic.child.support;

import com.example.starter.cosmetic.child.service.ChildFilingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 儿童公示期满自动生效定时器。与成人定时器相互独立，测试可通过
 * cosmetic.public-notice.scheduling-enabled=false 关闭。
 */
@Component
@ConditionalOnProperty(name = "cosmetic.public-notice.scheduling-enabled", havingValue = "true",
        matchIfMissing = true)
public class ChildPublicNoticeScheduler {

    private static final Logger log = LoggerFactory.getLogger(ChildPublicNoticeScheduler.class);

    private final ChildFilingService childFilingService;

    public ChildPublicNoticeScheduler(ChildFilingService childFilingService) {
        this.childFilingService = childFilingService;
    }

    /** 每 30 秒扫描一次；仅最新修订可生效，恰期满当时即可生效。 */
    @Scheduled(fixedDelayString = "${cosmetic.public-notice.scan-delay-ms:30000}")
    public void scan() {
        int effected = childFilingService.expireDuePublicNotices(null);
        if (effected > 0) {
            log.info("儿童公示期满自动生效备案数量: {}", effected);
        }
    }
}
