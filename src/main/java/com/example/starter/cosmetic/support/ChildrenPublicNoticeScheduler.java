package com.example.starter.cosmetic.support;

import com.example.starter.cosmetic.service.ChildrenFilingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 儿童备案公示期满自动生效定时器：按固定延迟扫描已到第 10 日 24:00(UTC) 的公示修订并原子生效。
 * 测试可通过 cosmetic.children-public-notice.scheduling-enabled=false 关闭。
 */
@Component
@ConditionalOnProperty(name = "cosmetic.children-public-notice.scheduling-enabled", havingValue = "true",
        matchIfMissing = true)
public class ChildrenPublicNoticeScheduler {

    private static final Logger log = LoggerFactory.getLogger(ChildrenPublicNoticeScheduler.class);

    private final ChildrenFilingService childrenFilingService;

    public ChildrenPublicNoticeScheduler(ChildrenFilingService childrenFilingService) {
        this.childrenFilingService = childrenFilingService;
    }

    /** 每 30 秒扫描一次；到期判定使用 &lt;=，恰期满当时即生效。 */
    @Scheduled(fixedDelayString = "${cosmetic.children-public-notice.scan-delay-ms:30000}")
    public void scan() {
        int effected = childrenFilingService.expireDuePublicNotices(null);
        if (effected > 0) {
            log.info("儿童备案公示期满自动生效数量: {}", effected);
        }
    }
}
