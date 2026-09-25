package com.example.starter.carbon;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import com.example.starter.carbon.repo.CarbonLedgerRepository;
import com.example.starter.carbon.service.SystemTimeProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * H2（MODE=MySQL）集成测试基类：每个场景前清空全部业务表并恢复真实 UTC 时钟。
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class AbstractCarbonIntegrationTest {

    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    protected CarbonLedgerRepository ledger;
    @Autowired
    protected SystemTimeProvider timeProvider;
    @Autowired
    protected PlatformTransactionManager txManager;

    @BeforeEach
    void cleanDatabase() {
        jdbc.update("DELETE FROM retirement_record");
        jdbc.update("DELETE FROM surrender_certificate");
        jdbc.update("DELETE FROM surrender_request");
        jdbc.update("DELETE FROM compliance_lock");
        jdbc.update("DELETE FROM offset_credit");
        jdbc.update("DELETE FROM allowance_batch");
        jdbc.update("DELETE FROM emission_assessment");
        jdbc.update("DELETE FROM enterprise");
        timeProvider.resetClock();
    }

    @AfterEach
    void restoreClock() {
        timeProvider.resetClock();
    }

    /** 将系统时钟固定到指定 UTC 时刻。 */
    protected void freezeAt(String instantUtc) {
        timeProvider.setClock(Clock.fixed(Instant.parse(instantUtc), ZoneOffset.UTC));
    }

    /** 在独立事务中创建合成企业，返回企业ID。 */
    protected long createEnterprise(String code, String name) {
        return new TransactionTemplate(txManager).execute(status -> ledger.insertEnterprise(code, name));
    }

    /** 在独立事务中创建核定信息。 */
    protected void createAssessment(long enterpriseId, int year, String emission, String deadlineUtc) {
        new TransactionTemplate(txManager).executeWithoutResult(status -> ledger.insertAssessment(
                enterpriseId, year, new BigDecimal(emission), Instant.parse(deadlineUtc)));
    }

    /** 在独立事务中创建批次。 */
    protected void createBatch(String type, long enterpriseId, String batchNo,
                               String amount, String issued, String expiry) {
        new TransactionTemplate(txManager).executeWithoutResult(status -> ledger.insertBatch(
                type, enterpriseId, batchNo, new BigDecimal(amount),
                LocalDate.parse(issued), LocalDate.parse(expiry)));
    }
}
