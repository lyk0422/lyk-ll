package com.example.starter.testsupport;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 服务级测试基类：启动完整 Spring 上下文 + 隔离的 H2(MODE=MySQL) 内存库，
 * 每个测试前清空业务表（幂等台账也清空，保证场景独立、无顺序依赖）。
 */
@SpringBootTest
@Import(TestClockConfig.class)
public abstract class AbstractServiceTest {

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected MutableClock clock;

    @org.junit.jupiter.api.BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM late_measurement_event");
        jdbcTemplate.update("DELETE FROM release_record");
        jdbcTemplate.update("DELETE FROM witness_record");
        jdbcTemplate.update("DELETE FROM inspection_record");
        jdbcTemplate.update("DELETE FROM rework_record");
        jdbcTemplate.update("DELETE FROM torque_record");
        jdbcTemplate.update("DELETE FROM torque_tool");
        jdbcTemplate.update("DELETE FROM fastener");
        jdbcTemplate.update("DELETE FROM station_guard");
        jdbcTemplate.update("DELETE FROM operation_card");
        jdbcTemplate.update("DELETE FROM idempotent_request");
        clock.setInstant(TestClockConfig.FIXED_NOW);
    }
}
