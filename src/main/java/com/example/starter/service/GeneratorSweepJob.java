package com.example.starter.service;

import com.example.starter.support.BusinessClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 发电车流程定时复算：扫描在途特级工单，停电恰满 5 分钟即启动发电车流程。
 * 派单队列、优先级与复电链本身按接口实时复算，无后台状态依赖。
 */
@Component
public class GeneratorSweepJob {

    private static final Logger log = LoggerFactory.getLogger(GeneratorSweepJob.class);

    private final FaultRepairService service;
    private final BusinessClock clock;

    public GeneratorSweepJob(FaultRepairService service, BusinessClock clock) {
        this.service = service;
        this.clock = clock;
    }

    /** 每 20 秒复算一次；业务动作（研判/派单/抢修/复电）时也会同步触发复算。 */
    @Scheduled(fixedDelay = 20_000L, initialDelay = 5_000L)
    public void sweep() {
        try {
            int triggered = service.evaluateGenerators(clock.now());
            if (triggered > 0) {
                log.info("发电车流程复算：新启动 {} 张特级工单", triggered);
            }
        } catch (Exception e) {
            log.warn("发电车流程复算失败: {}", e.getMessage());
        }
    }
}
