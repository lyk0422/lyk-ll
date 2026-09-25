package com.example.starter;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 集成测试基类：独立 H2 内存库，每个用例前清理业务数据并重置时钟，避免顺序依赖。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestClockConfig.class)
public abstract class BaseIntegrationTest {

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected MutableClock clock;

    @BeforeEach
    void cleanDatabase() {
        jdbc.update("DELETE FROM late_weigh_event");
        jdbc.update("DELETE FROM revocation_document");
        jdbc.update("DELETE FROM recheck_request");
        jdbc.update("DELETE FROM penalty_decision");
        jdbc.update("DELETE FROM case_record");
        jdbc.update("DELETE FROM weigh_record");
        jdbc.update("ALTER TABLE weigh_record ALTER COLUMN id RESTART WITH 1");
        jdbc.update("ALTER TABLE penalty_decision ALTER COLUMN id RESTART WITH 1");
        jdbc.update("ALTER TABLE case_record ALTER COLUMN id RESTART WITH 1");
        jdbc.update("ALTER TABLE recheck_request ALTER COLUMN id RESTART WITH 1");
        jdbc.update("ALTER TABLE revocation_document ALTER COLUMN id RESTART WITH 1");
        jdbc.update("ALTER TABLE late_weigh_event ALTER COLUMN id RESTART WITH 1");
        clock.set(TestClockConfig.START);
    }
}
