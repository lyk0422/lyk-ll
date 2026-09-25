package com.example.starter.quarantine;

import com.example.starter.quarantine.testsupport.MutableClock;
import com.example.starter.quarantine.testsupport.TestClockConfig;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * H2 集成测试基类：每个场景独立清理全部业务表并重置证号序列，避免顺序依赖。
 * 时钟固定为 {@link TestClockConfig#BASE}，场景内可显式推进。
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestClockConfig.class)
public abstract class AbstractIntegrationTest {

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected MutableClock clock;

    @BeforeEach
    void resetDatabaseAndClock() {
        jdbcTemplate.update("DELETE FROM landing_report");
        jdbcTemplate.update("DELETE FROM quarantine_certificate");
        jdbcTemplate.update("DELETE FROM quarantine_request");
        jdbcTemplate.update("DELETE FROM blockade");
        jdbcTemplate.update("DELETE FROM idempotent_command");
        jdbcTemplate.update("UPDATE farm_stock SET available_count = total_count, version = 0");
        jdbcTemplate.update("ALTER SEQUENCE cert_no_seq RESTART WITH 1");
        clock.setInstant(TestClockConfig.BASE);
    }
}
