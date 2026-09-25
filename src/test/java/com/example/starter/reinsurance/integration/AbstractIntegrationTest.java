package com.example.starter.reinsurance.integration;

import com.example.starter.reinsurance.api.AdjustEstimateRequest;
import com.example.starter.reinsurance.api.CloseClaimRequest;
import com.example.starter.reinsurance.api.CreateClaimRequest;
import com.example.starter.reinsurance.api.CreatePolicyRequest;
import com.example.starter.reinsurance.api.CreateTreatyRequest;
import com.example.starter.reinsurance.service.BillService;
import com.example.starter.reinsurance.service.ClaimService;
import com.example.starter.reinsurance.service.ReferenceDataService;
import com.example.starter.reinsurance.testsupport.SettableClock;
import com.example.starter.reinsurance.testsupport.TestClockConfig;
import com.example.starter.reinsurance.testsupport.TestDataCleaner;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 全部 H2 业务测试基类：启动完整 Spring 上下文（嵌入式 H2，MySQL 兼容模式），
 * 每个场景前清空业务表避免顺序依赖，并使用可控 UTC 时钟。
 */
@SpringBootTest(classes = {com.example.starter.StarterApplication.class, TestClockConfig.class})
abstract class AbstractIntegrationTest {

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected ReferenceDataService referenceDataService;

    @Autowired
    protected ClaimService claimService;

    @Autowired
    protected BillService billService;

    @Autowired
    protected SettableClock clock;

    @BeforeEach
    void cleanAndResetClock() {
        TestDataCleaner.clean(jdbcTemplate);
        clock.setInstant(TestClockConfig.DEFAULT_NOW);
    }

    protected void treaty(String contractNo, String rate, String limit) {
        referenceDataService.createTreaty(new CreateTreatyRequest(
                contractNo,
                new BigDecimal(rate),
                limit == null ? null : new BigDecimal(limit)));
    }

    protected void policy(String policyNo, String contractNo, String premium, LocalDate effectiveDate) {
        referenceDataService.createPolicy(
                new CreatePolicyRequest(policyNo, contractNo, new BigDecimal(premium), effectiveDate));
    }

    protected void claim(String claimNo, String policyNo, String estimate, LocalDate lossDate) {
        claimService.createClaim(new CreateClaimRequest(
                claimNo, policyNo, new BigDecimal(estimate), lossDate));
    }

    protected void adjust(String claimNo, String newEstimate) {
        claimService.adjustEstimate(claimNo, new AdjustEstimateRequest(new BigDecimal(newEstimate)));
    }

    protected com.example.starter.reinsurance.api.ClaimResponse close(
            String claimNo, String requestId, String paid) {
        return claimService.closeClaim(claimNo, new CloseClaimRequest(requestId, new BigDecimal(paid)));
    }

    protected BigDecimal accountBalance(Long contractId) {
        return jdbcTemplate.queryForObject(
                "SELECT balance FROM reinsurance_account WHERE contract_id = ?",
                BigDecimal.class,
                contractId);
    }
}
