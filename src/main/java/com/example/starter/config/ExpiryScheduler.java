package com.example.starter.config;

import java.time.Clock;
import java.time.Instant;

import com.example.starter.service.QualificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 资格到期自动巡检：发现失效资格即触发线路停运、改派与通知（同一事务）。
 * 可通过 app.expiry.scheduler-enabled=false 关闭（测试环境关闭）。
 */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "app.expiry.scheduler-enabled", havingValue = "true")
public class ExpiryScheduler {

    private static final Logger log = LoggerFactory.getLogger(ExpiryScheduler.class);

    private final QualificationService qualificationService;
    private final Clock clock;

    public ExpiryScheduler(QualificationService qualificationService, Clock clock) {
        this.qualificationService = qualificationService;
        this.clock = clock;
    }

    /**
     * 周期性执行到期巡检。
     */
    @Scheduled(fixedDelayString = "${app.expiry.scheduler-fixed-delay-ms:60000}")
    public void run() {
        var result = qualificationService.runExpiry(Instant.now(clock));
        if (!result.stoppedRouteIds().isEmpty()) {
            log.info("资格到期巡检：停运线路 {}，改派 {} 条，通知 {} 条",
                    result.stoppedRouteIds(), result.reassignmentCount(), result.notificationCount());
        }
    }
}
