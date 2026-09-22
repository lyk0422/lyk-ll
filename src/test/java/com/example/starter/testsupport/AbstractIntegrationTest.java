package com.example.starter.testsupport;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 集成测试基类：每个用例前清空全部业务表，保证场景间无顺序依赖。
 * 表间未建立外键约束，可直接按固定顺序 TRUNCATE。
 */
public abstract class AbstractIntegrationTest {

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanTables() {
        jdbcTemplate.execute("TRUNCATE TABLE state_change_event");
        jdbcTemplate.execute("TRUNCATE TABLE signal_state");
        jdbcTemplate.execute("TRUNCATE TABLE frame_audit");
        jdbcTemplate.execute("TRUNCATE TABLE dbc_signal_enum");
        jdbcTemplate.execute("TRUNCATE TABLE dbc_signal_def");
        jdbcTemplate.execute("TRUNCATE TABLE dbc_message_def");
        jdbcTemplate.execute("TRUNCATE TABLE dbc_version");
    }
}
