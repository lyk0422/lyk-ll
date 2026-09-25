package com.example.starter.wbb.config;

import com.example.starter.wbb.po.AircraftConfig;
import com.example.starter.wbb.repo.WbbRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 合成基础数据初始化：插入一套虚拟机型与重心包线（数据仅存于内存库，JVM 退出即失）。
 * 幂等执行，已存在同机型时跳过。
 */
@Component
public class SyntheticDataSeeder implements ApplicationRunner {

    public static final String AIRCRAFT_TYPE = "A320-SYN";
    /** 使用空重 kg。 */
    public static final BigDecimal OEW = new BigDecimal("40000.00");
    /** 最大无油重量 kg；最大业载 = MZFW - OEW = 20000kg。 */
    public static final BigDecimal MZFW = new BigDecimal("60000.00");
    /** 使用空重力矩 kg·m（空机重心力臂 16.0m）。 */
    public static final BigDecimal OE_MOMENT = new BigDecimal("640000.00");
    /** 默认燃油合力力臂 m。 */
    public static final BigDecimal DEFAULT_FUEL_ARM = new BigDecimal("14.0000");

    private static final Logger log = LoggerFactory.getLogger(SyntheticDataSeeder.class);

    private final WbbRepository repo;

    public SyntheticDataSeeder(WbbRepository repo) {
        this.repo = repo;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (repo.findConfig(AIRCRAFT_TYPE).isPresent()) {
            return;
        }
        long configId = repo.insertConfig(new AircraftConfig(null, AIRCRAFT_TYPE, MZFW, OEW,
                OE_MOMENT, DEFAULT_FUEL_ARM));
        // 合成包线（随重量收窄）：折点重量 kg / 前限 m / 后限 m，边界含。
        repo.insertEnvelopePoint(configId, 1, new BigDecimal("40000.00"),
                new BigDecimal("15.5000"), new BigDecimal("16.5000"));
        repo.insertEnvelopePoint(configId, 2, new BigDecimal("60000.00"),
                new BigDecimal("15.6000"), new BigDecimal("16.4000"));
        repo.insertEnvelopePoint(configId, 3, new BigDecimal("75000.00"),
                new BigDecimal("15.7000"), new BigDecimal("16.3000"));
        log.info("已写入合成机型 {} 与 3 点重心包线（最大业载 20000kg）", AIRCRAFT_TYPE);
    }
}
