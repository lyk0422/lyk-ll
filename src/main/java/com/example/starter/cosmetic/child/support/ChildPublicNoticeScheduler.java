package com.example.starter.cosmetic.child.support;

import com.example.starter.cosmetic.child.service.ChildFilingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 儿童备案公示期满自动生效定时器：按固定延迟扫描已到第 10 日 24:00(UTC) 的公示修订并原子生效。
 * 测试可通过 cosmetic.public-notice.scheduling-enabled=false 关闭，避免后台扫描干扰竞态断言。
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

    /** 每 30 秒扫描一次；到期判定使用 &lt;=，恰期满当时即生效。 */
    @Scheduled(fixedDelayString = "${cosmetic.public-notice.scan-delay-ms:30000}")
    public void scan() {
        int effected = childFilingService.expireDuePublicities(null);
        if (effected > 0) {
            log.info("儿童备案公示期满自动生效数量: {}", effected);
        }
    }
}
