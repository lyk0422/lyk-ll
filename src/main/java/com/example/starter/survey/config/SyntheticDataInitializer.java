package com.example.starter.survey.config;

import com.example.starter.survey.data.FlagRuleRepository;
import com.example.starter.survey.data.ShipRepository;
import com.example.starter.survey.domain.FlagRuleVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * 合成数据初始化：内存库为空时写入两艘合成船舶与一套合成互认规则版本。
 * 数据仅在 JVM 生命周期内保留；周年日均为 UTC 日期；规则不对应真实机构。
 */
@Component
public class SyntheticDataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SyntheticDataInitializer.class);

    /** 合成船旗代码。 */
    public static final String SYNTHETIC_FLAG = "SYN";
    /** 合成规则证据最长有效天数。 */
    public static final int SYNTHETIC_MAX_EVIDENCE_AGE_DAYS = 90;

    private final ShipRepository shipRepository;
    private final FlagRuleRepository flagRuleRepository;

    public SyntheticDataInitializer(ShipRepository shipRepository,
                                    FlagRuleRepository flagRuleRepository) {
        this.shipRepository = shipRepository;
        this.flagRuleRepository = flagRuleRepository;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (shipRepository.findAll().isEmpty()) {
            shipRepository.insert("IMO-9000001", "远洋先锋",
                    LocalDate.of(2020, 6, 15), LocalDate.of(2020, 3, 1),
                    java.time.Instant.now());
            shipRepository.insert("IMO-9000002", "星海号",
                    LocalDate.of(2019, 9, 30), LocalDate.of(2019, 1, 15),
                    java.time.Instant.now());
            log.info("已写入合成船舶数据：IMO-9000001 远洋先锋、IMO-9000002 星海号");
        }
        if (flagRuleRepository.findLatest(SYNTHETIC_FLAG).isEmpty()) {
            FlagRuleVersion rule = flagRuleRepository.insertVersion("seed-rule-syn-v1",
                    SYNTHETIC_FLAG, 1, SYNTHETIC_MAX_EVIDENCE_AGE_DAYS,
                    java.time.Instant.now());
            flagRuleRepository.insertItem(rule.id(), "FLAG-HULL", "CLASS-HULL");
            flagRuleRepository.insertItem(rule.id(), "FLAG-MACHINERY", "CLASS-MACHINERY");
            flagRuleRepository.insertItem(rule.id(), "FLAG-SAFETY", "CLASS-SAFETY");
            log.info("已写入合成互认规则：船旗 {} 版本 1，证据最长有效 {} 天，映射 3 项",
                    SYNTHETIC_FLAG, SYNTHETIC_MAX_EVIDENCE_AGE_DAYS);
        }
    }
}
