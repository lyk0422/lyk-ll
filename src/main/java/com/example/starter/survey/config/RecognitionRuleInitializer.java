package com.example.starter.survey.config;

import com.example.starter.survey.data.RecognitionRuleRepository;
import com.example.starter.survey.domain.RecognitionItemMapping;
import com.example.starter.survey.domain.SurveyType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * 合成互认规则初始化：内存库无任何规则版本时写入版本 1。
 * 明确承认的船旗代码、船旗国检查项目→船级检验项目映射、最长证据有效天数。
 * 全部为虚构值，仅用于演示，不代表任何真实船旗国机构。
 */
@Component
@Order(20)
public class RecognitionRuleInitializer implements ApplicationRunner {

    /** 合成规则版本 1 的证据最长有效天数。 */
    public static final int V1_MAX_EVIDENCE_VALID_DAYS = 90;

    private static final Logger log = LoggerFactory.getLogger(RecognitionRuleInitializer.class);

    private final RecognitionRuleRepository ruleRepository;

    public RecognitionRuleInitializer(RecognitionRuleRepository ruleRepository) {
        this.ruleRepository = ruleRepository;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!ruleRepository.findAllVersions().isEmpty()) {
            return;
        }
        List<String> flagCodes = List.of("SYN-PA", "SYN-XH");
        List<RecognitionItemMapping> mappings = List.of(
                new RecognitionItemMapping(null, null,
                        "FIRE-SAFETY-ANNUAL", SurveyType.ANNUAL, "FIRE_SAFETY"),
                new RecognitionItemMapping(null, null,
                        "LIFESAVING-ANNUAL", SurveyType.ANNUAL, "LIFESAVING"),
                new RecognitionItemMapping(null, null,
                        "HULL-INTERMEDIATE", SurveyType.INTERMEDIATE, "HULL"),
                new RecognitionItemMapping(null, null,
                        "MACHINERY-SPECIAL", SurveyType.SPECIAL, "MACHINERY"));
        ruleRepository.publishVersion(1, V1_MAX_EVIDENCE_VALID_DAYS,
                "合成互认规则版本 1（仅演示，不用于生产）", Instant.now(), flagCodes, mappings);
        log.info("已写入合成互认规则版本 1：船旗 {}，映射 {} 项，证据最长有效 {} 天",
                flagCodes, mappings.size(), V1_MAX_EVIDENCE_VALID_DAYS);
    }
}
