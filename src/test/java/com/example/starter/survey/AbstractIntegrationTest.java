package com.example.starter.survey;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * H2 集成测试基类：每个场景前清理业务表（保留船舶/类型合成种子与幂等表重置），
 * 避免用例间顺序依赖。
 */
@SpringBootTest
@Import(MutableClockConfig.class)
public abstract class AbstractIntegrationTest {

    @Autowired
    protected JdbcTemplate jdbc;

    @BeforeEach
    void resetState() {
        MutableClockConfig.setToday(java.time.LocalDate.of(2026, 6, 10));
        jdbc.update("DELETE FROM survey_event");
        jdbc.update("DELETE FROM defect");
        jdbc.update("DELETE FROM extension_application");
        jdbc.update("DELETE FROM certificate");
        jdbc.update("DELETE FROM annual_window_ledger");
        jdbc.update("DELETE FROM survey");
        jdbc.update("DELETE FROM idempotent_request");
    }

    protected long count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }
}
