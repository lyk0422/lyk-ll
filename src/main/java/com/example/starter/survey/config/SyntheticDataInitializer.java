package com.example.starter.survey.config;

import com.example.starter.survey.data.RecognitionRuleRepository;
import com.example.starter.survey.data.ShipRepository;
import com.example.starter.survey.domain.SurveyItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * 合成数据初始化：内存库为空时写入两艘合成船舶与一个合成互认规则版本。
 * 数据仅在 JVM 生命周期内保留；周年日均为 UTC 日期；规则仅用于合成演示，不用于生产。
 */
@Component
public class SyntheticDataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SyntheticDataInitializer.class);

    /** 合成互认规则版本号（船旗 SYN-FL，映射年度检验必需项目，证据最长有效 90 天）。 */
    public static final int SYNTHETIC_RULE_VERSION = 1;
    /** 合成船旗代码。 */
    public static final String SYNTHETIC_FLAG = "SYN-FL";
    /** 合成规则的证据最长有效天数。 */
    public static final int SYNTHETIC_MAX_EVIDENCE_DAYS = 90;

    private final ShipRepository shipRepository;
    private final RecognitionRuleRepository ruleRepository;

    public SyntheticDataInitializer(ShipRepository shipRepository,
                                    RecognitionRuleRepository ruleRepository) {
        this.shipRepository = shipRepository;
        this.ruleRepository = ruleRepository;
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
        if (ruleRepository.findByVersion(SYNTHETIC_RULE_VERSION).isEmpty()) {
            ruleRepository.insert("seed-rule-v1", SYNTHETIC_RULE_VERSION, SYNTHETIC_FLAG,
                    SYNTHETIC_MAX_EVIDENCE_DAYS,
                    List.of(SurveyItem.HULL_STRUCTURE, SurveyItem.MACHINERY,
                            SurveyItem.SAFETY_EQUIPMENT),
                    "seed", java.time.Instant.now());
            log.info("已写入合成互认规则版本 {}：船旗 {}，映射项目 HULL_STRUCTURE/MACHINERY/"
                            + "SAFETY_EQUIPMENT，证据最长有效 {} 天",
                    SYNTHETIC_RULE_VERSION, SYNTHETIC_FLAG, SYNTHETIC_MAX_EVIDENCE_DAYS);
        }
    }
}
