package com.example.starter.service;

import com.example.starter.db.FanRepository;
import com.example.starter.db.SiloRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

/**
 * 本地启动合成数据：仅在非 test profile 下预置两座粮仓与两台风机。
 * 数据仅保留在 JVM 生命周期内的 H2 内存库，重启即重新初始化。
 */
@Component
@Profile("!test")
public class SyntheticDataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SyntheticDataInitializer.class);

    private final SiloRepository siloRepository;
    private final FanRepository fanRepository;

    public SyntheticDataInitializer(SiloRepository siloRepository, FanRepository fanRepository) {
        this.siloRepository = siloRepository;
        this.fanRepository = fanRepository;
    }

    @Override
    public void run(ApplicationArguments args) {
        createSiloIfAbsent("SILO-A1", "一号小麦仓", "WHEAT", 20.0, 2.0, 70.0);
        createSiloIfAbsent("SILO-A2", "二号玉米仓", "CORN", 22.0, 2.5, 72.0);
        createFanIfAbsent("FAN-01", "1号离心风机");
        createFanIfAbsent("FAN-02", "2号轴流风机");
        log.info("合成数据初始化完成：粮仓 SILO-A1/SILO-A2，风机 FAN-01/FAN-02（仅存于内存，重启重置）");
    }

    private void createSiloIfAbsent(String code, String name, String grainType, double safeTemperature,
                                    double maxGradient, double equilibriumHumidity) {
        if (siloRepository.findByCode(code).isEmpty()) {
            try {
                siloRepository.insert(new SiloRepository.CreateSiloRow(code, name, grainType,
                        safeTemperature, maxGradient, equilibriumHumidity));
            } catch (DuplicateKeyException ignored) {
                // 并发启动或重复初始化时以已有数据为准
            }
        }
    }

    private void createFanIfAbsent(String code, String name) {
        if (fanRepository.findByCode(code).isEmpty()) {
            try {
                fanRepository.insert(code, name);
            } catch (DuplicateKeyException ignored) {
                // 并发启动或重复初始化时以已有数据为准
            }
        }
    }
}
