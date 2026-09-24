package com.example.starter.payment;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 集成测试基类：每个用例前清空业务表，避免顺序依赖。
 */
@SpringBootTest
public abstract class AbstractPaymentIntegrationTest {

    @Autowired
    protected JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM recon_resolution_log");
        jdbc.update("DELETE FROM recon_difference");
        jdbc.update("DELETE FROM settlement_bill");
        jdbc.update("DELETE FROM pay_transaction");
        jdbc.update("DELETE FROM pay_batch");
    }

    protected long countRows(String table) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return count == null ? 0 : count;
    }
}
