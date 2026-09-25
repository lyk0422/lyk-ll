package com.example.starter.dye.support;

import com.example.starter.dye.repo.DyeVatRepository;
import com.example.starter.dye.repo.DyestuffRepository;
import com.example.starter.dye.repo.RecipeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * 合成数据初始化：内存库为空时写入一套演示用染化料、染缸与已发布配方。
 * 幂等：已有任意染化料数据时跳过，不影响测试隔离清理后的场景。
 */
@Component
public class SyntheticDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SyntheticDataSeeder.class);

    private final DyestuffRepository dyestuffRepository;
    private final DyeVatRepository dyeVatRepository;
    private final RecipeRepository recipeRepository;

    public SyntheticDataSeeder(DyestuffRepository dyestuffRepository, DyeVatRepository dyeVatRepository,
                               RecipeRepository recipeRepository) {
        this.dyestuffRepository = dyestuffRepository;
        this.dyeVatRepository = dyeVatRepository;
        this.recipeRepository = recipeRepository;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!dyestuffRepository.all().isEmpty()) {
            return;
        }
        var now = java.time.LocalDateTime.now();
        dyestuffRepository.upsert("DYE-RED-3B", "活性红3B（合成）", new BigDecimal("50000.000"), now);
        dyestuffRepository.upsert("DYE-YELLOW-Y", "活性黄Y（合成）", new BigDecimal("40000.000"), now);
        dyestuffRepository.upsert("DYE-BLUE-RR", "活性蓝RR（合成）", new BigDecimal("30000.000"), now);
        dyeVatRepository.upsert("VAT-A01", new BigDecimal("500.00"));
        dyeVatRepository.upsert("VAT-A02", new BigDecimal("300.00"));

        long recipeId = recipeRepository.insert("RC-COTTON-NAVY", 1, "深藏青棉布配方（合成）",
                10, com.example.starter.dye.domain.RecipeStatus.PUBLISHED, now);
        recipeRepository.insertItem(recipeId, "DYE-RED-3B", new BigDecimal("2.0000"), 1);
        recipeRepository.insertItem(recipeId, "DYE-YELLOW-Y", new BigDecimal("1.5000"), 2);
        recipeRepository.insertItem(recipeId, "DYE-BLUE-RR", new BigDecimal("3.0000"), 3);
        log.info("合成印染演示数据已初始化：3 种染化料、2 台染缸、1 个已发布配方 RC-COTTON-NAVY V1");
    }
}
