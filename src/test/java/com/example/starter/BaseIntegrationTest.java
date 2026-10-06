package com.example.starter;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 集成测试基类：每个用例前重置可控时钟并清空业务表（保留合成叶片档案），
 * 保证用例间无顺序依赖。
 */
@SpringBootTest
@Import(TestClockConfig.class)
public abstract class BaseIntegrationTest {

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected MutableClock clock;

    @BeforeEach
    void resetState() {
        clock.setInstant(TestClockConfig.T0);
        jdbc.update("DELETE FROM reinspection_record");
        jdbc.update("DELETE FROM alert_record");
        jdbc.update("DELETE FROM repair_order");
        jdbc.update("DELETE FROM power_limit");
        jdbc.update("DELETE FROM root_cause_assessment");
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM defect_record");
    }
}
