package com.example.starter.survey.config;

import com.example.starter.survey.data.ShipRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * 合成数据初始化：内存库为空时写入两艘合成船舶。
 * 数据仅在 JVM 生命周期内保留；周年日均为 UTC 日期。
 */
@Component
public class SyntheticDataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SyntheticDataInitializer.class);

    private final ShipRepository shipRepository;

    public SyntheticDataInitializer(ShipRepository shipRepository) {
        this.shipRepository = shipRepository;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!shipRepository.findAll().isEmpty()) {
            return;
        }
        shipRepository.insert("IMO-9000001", "远洋先锋",
                LocalDate.of(2020, 6, 15), LocalDate.of(2020, 3, 1),
                java.time.Instant.now());
        shipRepository.insert("IMO-9000002", "星海号",
                LocalDate.of(2019, 9, 30), LocalDate.of(2019, 1, 15),
                java.time.Instant.now());
        log.info("已写入合成船舶数据：IMO-9000001 远洋先锋、IMO-9000002 星海号");
    }
}
