package com.example.starter;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** H2 集成测试基类：每个场景前清空业务表，商户合成数据保留。 */
@SpringBootTest
public abstract class AbstractIntegrationTest {

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanTables() {
        jdbcTemplate.update("DELETE FROM recon_diff");
        jdbcTemplate.update("DELETE FROM recon_file");
        jdbcTemplate.update("DELETE FROM adjustment");
        jdbcTemplate.update("DELETE FROM settlement_order");
        jdbcTemplate.update("DELETE FROM payment_txn");
        jdbcTemplate.update("DELETE FROM settlement_batch");
    }
}
