package com.example.starter.carbon.support;

import com.example.starter.carbon.service.SurrenderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 履约截止扫描：按固定间隔自动将已过截止时刻（UTC）且未足额的企业年度标记为“未履约”。
 * 可通过 carbon.schedule.enabled=false 关闭（隔离测试使用）。
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "carbon.schedule.enabled", havingValue = "true", matchIfMissing = true)
public class DeadlineSchedulerConfig {

    private static final Logger log = LoggerFactory.getLogger(DeadlineSchedulerConfig.class);

    private final SurrenderService surrenderService;

    public DeadlineSchedulerConfig(SurrenderService surrenderService) {
        this.surrenderService = surrenderService;
    }

    /**
     * 每 30 秒扫描一次（启动后 5 秒首扫）；截止判定口径由服务统一保证：严格晚于截止时刻才标记。
     */
    @Scheduled(
            fixedDelayString = "${carbon.deadline.scan-delay-ms:30000}",
            initialDelayString = "${carbon.deadline.scan-initial-delay-ms:5000}")
    public void scanOverdue() {
        try {
            int marked = surrenderService.markOverdueAssessments();
            if (marked > 0) {
                log.info("自动扫描新标记未履约企业年度 {} 个", marked);
            }
        } catch (Exception ex) {
            log.warn("履约截止扫描失败，将在下个周期重试: {}", ex.getMessage());
        }
    }
}
