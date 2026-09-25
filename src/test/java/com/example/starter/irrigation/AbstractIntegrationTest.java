package com.example.starter.irrigation;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 集成测试基座：随机端口启动完整应用上下文，每个用例前清理全部业务表，避免顺序依赖。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestDatabaseReleaseConfig.class)
public abstract class AbstractIntegrationTest {

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected TestRestTemplate rest;

    @BeforeEach
    void cleanDatabase() {
        jdbc.update("DELETE FROM extra_purchase_detail");
        jdbc.update("DELETE FROM extra_purchase");
        jdbc.update("DELETE FROM settlement_detail");
        jdbc.update("DELETE FROM round_settlement");
        jdbc.update("DELETE FROM late_metering_event");
        jdbc.update("DELETE FROM metering");
        jdbc.update("DELETE FROM round_effective_plan");
        jdbc.update("DELETE FROM plan_detail");
        jdbc.update("DELETE FROM plan_version");
        jdbc.update("DELETE FROM outlet");
        jdbc.update("DELETE FROM rotation_group");
    }
}
