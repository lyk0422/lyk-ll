package com.example.starter.feed.bootstrap;

import com.example.starter.feed.domain.Ingredient;
import com.example.starter.feed.repo.FeedRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 启动时写入合成目录（原料、药物添加剂、配伍禁忌）与合成养殖批次；全部为虚构数据，仅存活于 JVM 内。
 * 已存在数据时跳过，保证可重复启动。
 */
@Component
public class SyntheticDataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SyntheticDataInitializer.class);

    private final FeedRepository repository;

    public SyntheticDataInitializer(FeedRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        Instant now = Instant.now();
        if (repository.countIngredients() == 0) {
            seedIngredients();
            seedContraindications();
        }
        ensureBatch("BATCH-POULTRY-01", "POULTRY", now);
        ensureBatch("BATCH-SWINE-01", "SWINE", now);
        log.info("饲料合规合成目录与批次初始化完成");
    }

    private void seedIngredients() {
        List<Ingredient> data = List.of(
                // 普通原料：不限制添加比例
                new Ingredient("CORN", "玉米", false, null, null),
                new Ingredient("SOYBEAN_MEAL", "豆粕", false, null, null),
                new Ingredient("DICALCIUM_PHOSPHATE", "磷酸氢钙", false, null, null),
                // 药物添加剂：maxRatio 最大添加比例（恰等于合法），withdrawalDays 休药天数（UTC 日界）
                new Ingredient("AMOXICILLIN", "阿莫西林", true, new BigDecimal("0.002"), 7),
                new Ingredient("OXYTETRACYCLINE", "土霉素", true, new BigDecimal("0.004"), 10),
                new Ingredient("SULFAMONOMETHOXINE", "磺胺间甲氧嘧啶", true, new BigDecimal("0.003"), 14),
                new Ingredient("ENRAMYCIN", "恩拉霉素", true, new BigDecimal("0.001"), 5));
        for (Ingredient ingredient : data) {
            repository.insertIngredient(ingredient);
        }
    }

    private void seedContraindications() {
        // 合成禁忌清单（无向对，内部按字典序存储）：
        // 青霉素类与四环素类、磺胺类与恩拉霉素禁止配伍。
        repository.insertContraindication("AMOXICILLIN", "OXYTETRACYCLINE");
        repository.insertContraindication("ENRAMYCIN", "SULFAMONOMETHOXINE");
    }

    private void ensureBatch(String batchNo, String species, Instant now) {
        if (repository.findBatch(batchNo) == null) {
            repository.insertBatchIfAbsent(batchNo, species, now);
        }
    }
}
