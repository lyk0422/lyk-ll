package com.example.starter.cosmetic.config;

import java.time.Clock;
import java.time.Instant;

import com.example.starter.cosmetic.service.RegistrationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 公示期满自动生效的定时任务：恰第10日24:00期满的公示备案自动生效。
 * 测试中通过 cosmetic.publicity-finalizer.enabled=false 关闭以保证时钟可控。
 */
@Component
@ConditionalOnProperty(name = "cosmetic.publicity-finalizer.enabled", havingValue = "true")
public class PublicityFinalizer {

    private static final Logger log = LoggerFactory.getLogger(PublicityFinalizer.class);

    private final RegistrationService registrationService;
    private final Clock clock;

    public PublicityFinalizer(RegistrationService registrationService, Clock clock) {
        this.registrationService = registrationService;
        this.clock = clock;
    }

    /**
     * 周期处理到期公示备案。
     */
    @Scheduled(fixedDelayString = "${cosmetic.publicity-finalizer.fixed-delay-ms:60000}")
    public void finalizeDue() {
        int count = registrationService.finalizeDue(Instant.now(clock));
        if (count > 0) {
            log.info("公示期满自动生效 {} 件备案", count);
        }
    }
}
