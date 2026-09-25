package com.example.starter.cosmetic.config;

import java.math.BigDecimal;
import java.util.List;

import com.example.starter.cosmetic.domain.EfficacyEntry;
import com.example.starter.cosmetic.domain.RestrictedEntry;
import com.example.starter.cosmetic.repo.CatalogRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 功效目录与限用成分目录的种子数据初始化（全部为合成数据，幂等：目录非空则跳过）。
 */
@Component
public class CatalogSeeder implements ApplicationRunner {

    private final CatalogRepository catalogRepository;

    public CatalogSeeder(CatalogRepository catalogRepository) {
        this.catalogRepository = catalogRepository;
    }

    /**
     * 应用启动时写入合成目录数据。
     */
    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (catalogRepository.countEfficacy() == 0) {
            List.of(
                    new EfficacyEntry("SUNSCREEN", "防晒", true, true),
                    new EfficacyEntry("WHITENING", "美白", true, true),
                    new EfficacyEntry("FRECKLE_REMOVAL", "祛斑", true, true),
                    new EfficacyEntry("MOISTURIZING", "保湿", false, true),
                    new EfficacyEntry("CLEANSING", "清洁", false, true),
                    new EfficacyEntry("HAIR_GROWTH", "生发", false, false)
            ).forEach(catalogRepository::insertEfficacy);
        }
        if (catalogRepository.countRestricted() == 0) {
            List.of(
                    new RestrictedEntry("SALICYLIC_ACID", "水杨酸", new BigDecimal("2.0")),
                    new RestrictedEntry("HOMOSALATE", "胡莫柳酯", new BigDecimal("10.0")),
                    new RestrictedEntry("KOJIC_ACID", "曲酸", new BigDecimal("1.0"))
            ).forEach(catalogRepository::insertRestricted);
        }
    }
}
