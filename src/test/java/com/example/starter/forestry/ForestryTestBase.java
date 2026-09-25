package com.example.starter.forestry;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.starter.forestry.repo.ForestryRepository;
import com.example.starter.forestry.service.ForestryService;

/**
 * 测试基类：每个用例前清空全部业务表并重置时钟，避免顺序依赖。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestClockConfig.class)
public abstract class ForestryTestBase {

    protected static final LocalDate T0 = LocalDate.of(2026, 3, 1);

    @Autowired
    protected ForestryService service;

    @Autowired
    protected ForestryRepository repo;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected MutableClock clock;

    @BeforeEach
    void resetState() {
        jdbc.update("DELETE FROM acceptance_record");
        jdbc.update("DELETE FROM regeneration_task");
        jdbc.update("DELETE FROM harvest_license");
        jdbc.update("DELETE FROM harvest_application");
        jdbc.update("DELETE FROM operator_blacklist");
        jdbc.update("DELETE FROM late_event");
        jdbc.update("DELETE FROM annual_quota");
        jdbc.update("DELETE FROM forest_plot");
        clock.setDate(T0);
    }

    /** 插入商品林小班。 */
    protected long givenCommercialPlot(String code, int standAge, int matureAge, String tier) {
        return repo.insertPlot(code, ForestryService.FOREST_TYPE_COMMERCIAL, false,
                standAge, matureAge, tier, new BigDecimal("10.00"));
    }

    /** 插入生态公益林小班。 */
    protected long givenEcologicalPlot(String code, String tier) {
        return repo.insertPlot(code, ForestryService.FOREST_TYPE_ECOLOGICAL, false,
                40, 30, tier, new BigDecimal("10.00"));
    }

    /** 插入禁伐区小班。 */
    protected long givenNoHarvestZonePlot(String code, String tier) {
        return repo.insertPlot(code, ForestryService.FOREST_TYPE_COMMERCIAL, true,
                40, 30, tier, new BigDecimal("10.00"));
    }

    /** 插入年度限额。 */
    protected long givenQuota(int year, String tier, String volume) {
        return repo.insertQuota(year, tier, new BigDecimal(volume));
    }

    /** 插入限批记录。 */
    protected void givenBlacklist(String operatorId, LocalDate bannedUntil) {
        repo.insertBlacklist(operatorId, bannedUntil, "REGENERATION_FAILED",
                T0.atStartOfDay());
    }
}
