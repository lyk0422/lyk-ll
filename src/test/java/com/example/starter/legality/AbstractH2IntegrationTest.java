package com.example.starter.legality;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * H2（MODE=MySQL）集成测试基类：每个场景前清空事务表，主数据保留，避免顺序依赖。
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class AbstractH2IntegrationTest {

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @org.junit.jupiter.api.BeforeEach
    void cleanTransactionalTables() {
        jdbcTemplate.update("DELETE FROM delay_event");
        jdbcTemplate.update("DELETE FROM duty_record");
        jdbcTemplate.update("DELETE FROM check_batch");
    }
}
