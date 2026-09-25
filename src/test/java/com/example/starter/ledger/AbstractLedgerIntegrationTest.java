package com.example.starter.ledger;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 集成测试基类：每个用例前清理全部业务表并复位时钟，保证场景间无顺序依赖。
 */
@SpringBootTest(classes = {com.example.starter.StarterApplication.class, TestClockConfig.class})
public abstract class AbstractLedgerIntegrationTest {

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        TestClockConfig.CLOCK.reset();
        jdbcTemplate.update("DELETE FROM ledger_event");
        jdbcTemplate.update("DELETE FROM transfer_order");
        jdbcTemplate.update("DELETE FROM idempotent_record");
        jdbcTemplate.update("DELETE FROM chain_alert");
        jdbcTemplate.update("DELETE FROM battery");
    }
}
