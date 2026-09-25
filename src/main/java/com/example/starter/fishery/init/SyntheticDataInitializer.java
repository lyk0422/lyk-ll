package com.example.starter.fishery.init;

import com.example.starter.fishery.db.CatalogRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

/**
 * 合成基础数据初始化：2 艘渔船、3 个鱼种（含 1 个兼捕保护鱼种）及当前 UTC 年度配额。
 * 仅在内存库为空时写入，所有数据均为合成数据。
 */
@Component
public class SyntheticDataInitializer implements ApplicationRunner {

    public static final String VESSEL_1 = "V-001";
    public static final String VESSEL_2 = "V-002";
    public static final String SPECIES_HAIRTAIL = "HAIRTAIL";
    public static final String SPECIES_YELLOW_CROAKER = "YELLOW_CROAKER";
    public static final String SPECIES_PROTECTED = "STURGEON";

    private final JdbcTemplate jdbcTemplate;
    private final CatalogRepository catalog;
    private final Clock clock;

    public SyntheticDataInitializer(JdbcTemplate jdbcTemplate, CatalogRepository catalog, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.catalog = catalog;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        Instant now = Instant.now(clock);
        seedCatalog(now);
        int year = now.atZone(ZoneOffset.UTC).getYear();
        seedQuota(VESSEL_1, SPECIES_HAIRTAIL, year, new BigDecimal("1000.000"));
        seedQuota(VESSEL_1, SPECIES_YELLOW_CROAKER, year, new BigDecimal("500.000"));
        seedQuota(VESSEL_2, SPECIES_HAIRTAIL, year, new BigDecimal("800.000"));
        seedQuota(VESSEL_2, SPECIES_PROTECTED, year, BigDecimal.ZERO);
    }

    private void seedCatalog(Instant now) {
        if (catalog.findVessel(VESSEL_1) == null) {
            catalog.insertVessel(VESSEL_1, "浙渔1001", now);
        }
        if (catalog.findVessel(VESSEL_2) == null) {
            catalog.insertVessel(VESSEL_2, "浙渔1002", now);
        }
        if (catalog.findSpecies(SPECIES_HAIRTAIL) == null) {
            catalog.insertSpecies(SPECIES_HAIRTAIL, "带鱼", false);
        }
        if (catalog.findSpecies(SPECIES_PROTECTED) == null) {
            catalog.insertSpecies(SPECIES_PROTECTED, "中华鲟", true);
        }
        if (catalog.findSpecies(SPECIES_YELLOW_CROAKER) == null) {
            catalog.insertSpecies(SPECIES_YELLOW_CROAKER, "大黄鱼", false);
        }
    }

    private void seedQuota(String vesselCode, String speciesCode, int year, BigDecimal quotaKg) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM quota WHERE vessel_code = ? AND species_code = ? AND quota_year = ?",
                Integer.class, vesselCode, speciesCode, year);
        if (count == null || count == 0) {
            jdbcTemplate.update(
                    "INSERT INTO quota(vessel_code, species_code, quota_year, quota_kg, used_kg, version) "
                            + "VALUES (?, ?, ?, ?, 0, 0)",
                    vesselCode, speciesCode, year, quotaKg);
        }
    }
}
