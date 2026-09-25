package com.example.starter;

import java.math.BigDecimal;

import com.example.starter.domain.Models.Policy;
import com.example.starter.domain.Models.Treaty;
import com.example.starter.service.ClaimService;
import com.example.starter.service.BillingService;
import com.example.starter.service.TreatyService;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 集成测试基类：真实 H2（MySQL 兼容模式）+ 可控时钟，每个用例前清空全部业务表。
 */
@SpringBootTest
@Import(TestInfraConfig.class)
public abstract class BaseIntegrationTest {

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected MutableClock clock;

    @Autowired
    protected TreatyService treatyService;

    @Autowired
    protected ClaimService claimService;

    @Autowired
    protected BillingService billingService;

    @BeforeEach
    void cleanDatabase() {
        jdbc.update("DELETE FROM bill_dispute_item");
        jdbc.update("DELETE FROM bill_dispute");
        jdbc.update("DELETE FROM bill_item");
        jdbc.update("DELETE FROM bill");
        jdbc.update("DELETE FROM account_entry");
        jdbc.update("DELETE FROM recovery_account");
        jdbc.update("DELETE FROM claim_event");
        jdbc.update("DELETE FROM claim");
        jdbc.update("DELETE FROM policy");
        jdbc.update("DELETE FROM treaty");
        jdbc.update("DELETE FROM idempotency_key");
        clock.reset();
    }

    protected Treaty newTreaty(String treatyNo, String rate, String limit) {
        return treatyService.createTreaty(treatyNo, "测试合约" + treatyNo,
                new BigDecimal(rate), new BigDecimal(limit));
    }

    protected Policy newPolicy(String treatyNo, String policyNo, String premium) {
        return treatyService.createPolicy(treatyNo, policyNo, new BigDecimal(premium));
    }

    protected BigDecimal balanceOf(String treatyNo) {
        return treatyService.getAccount(treatyNo).account().balance();
    }
}
