package com.example.starter.forestry;

import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.starter.forestry.dto.ApplyRequest;
import com.example.starter.forestry.dto.IssueRequest;
import com.example.starter.forestry.dto.ReviewRequest;
import com.example.starter.forestry.service.ForestryService;
import com.example.starter.forestry.support.BusinessClock;

/**
 * 测试基类：每个用例前清理业务表（保留合成小班/限额基础数据）并将可控时钟拨至基准日。
 */
@SpringBootTest
abstract class AbstractForestryTest {

    protected static final LocalDate BASE_DATE = LocalDate.of(2026, 3, 1);

    @Autowired
    protected ForestryService service;

    @Autowired
    protected BusinessClock clock;

    @Autowired
    protected JdbcTemplate jdbc;

    @BeforeEach
    void cleanUp() {
        jdbc.update("DELETE FROM late_event");
        jdbc.update("DELETE FROM idempotent_request");
        jdbc.update("DELETE FROM restriction_list");
        jdbc.update("DELETE FROM renewal_obligation");
        jdbc.update("DELETE FROM quota_ledger");
        jdbc.update("DELETE FROM harvest_license");
        jdbc.update("DELETE FROM harvest_application");
        clock.setToday(BASE_DATE);
    }

    /** 申请 → 审核通过 → 发证 的完整准备，返回许可证视图。 */
    protected com.example.starter.forestry.dto.LicenseView issueLicense(
            String requestPrefix, String operatorId, String subplotId,
            String speciesCode, String harvestType, int treeCount, java.math.BigDecimal designVolume) {
        var app = service.apply(new ApplyRequest(
                requestPrefix + "-apply", operatorId, subplotId, speciesCode, harvestType,
                treeCount, designVolume));
        service.review(app.id(), new ReviewRequest(true, "OK"));
        return service.issue(new IssueRequest(requestPrefix + "-issue", app.id()));
    }
}
