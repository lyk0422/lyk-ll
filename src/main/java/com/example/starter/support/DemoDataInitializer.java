package com.example.starter.support;

import com.example.starter.api.dto.SetupDtos;
import com.example.starter.service.SetupService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * 本地演示合成数据：设置 greenhouse.demo-seed=true 时启动自动写入一个分区与番茄结果期窗口。
 * 默认关闭，不影响正式数据与测试隔离。
 */
@Component
@ConditionalOnProperty(name = "greenhouse.demo-seed", havingValue = "true")
public class DemoDataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataInitializer.class);

    private final SetupService setupService;

    public DemoDataInitializer(SetupService setupService) {
        this.setupService = setupService;
    }

    @Override
    public void run(ApplicationArguments args) {
        setupService.upsertCrop(new SetupDtos.CropSetupRequest("TOMATO", "番茄", List.of(
                new SetupDtos.StageWindow("FRUIT", "结果期",
                        new BigDecimal("20.00"), new BigDecimal("28.00"),
                        new BigDecimal("14.00"), new BigDecimal("20.00"),
                        new BigDecimal("60.00"), new BigDecimal("80.00")))));
        try {
            setupService.createZone(new SetupDtos.ZoneSetupRequest("Z1", "一号番茄分区"));
        } catch (RuntimeException e) {
            // 重复启动时分区已存在属预期，忽略。
            log.debug("演示分区已存在：{}", e.getMessage());
        }
        log.info("演示合成数据已就绪：作物 TOMATO/FRUIT、分区 Z1");
    }
}
