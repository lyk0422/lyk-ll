package com.example.starter.carbon;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 履约截止标记任务：截止时刻（UTC）到达后，周期性将仍未足额清缴的企业标记为“未履约”。
 * 与申请受理并发时互不影响：申请是否受理仅由请求到达时刻与截止时刻的先后决定。
 */
@Component
public class ComplianceMarker {

    private static final Logger log = LoggerFactory.getLogger(ComplianceMarker.class);

    private final SurrenderService service;

    public ComplianceMarker(SurrenderService service) {
        this.service = service;
    }

    /**
     * 每 15 秒检查一次；未到截止时刻不动作。
     */
    @Scheduled(fixedDelayString = "PT15S")
    public void markNonCompliant() {
        int marked = service.markNonCompliantIfDue();
        if (marked > 0) {
            log.info("履约截止已过，标记未履约企业年度记录 {} 条", marked);
        }
    }
}
