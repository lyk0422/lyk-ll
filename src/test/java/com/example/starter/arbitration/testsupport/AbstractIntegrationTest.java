package com.example.starter.arbitration.testsupport;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * H2 集成测试基类：每个场景前清空数据并复位急停状态与时钟，避免顺序依赖。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestClockConfiguration.class)
public abstract class AbstractIntegrationTest {

    protected static final long START_MS = 1_700_000_000_000L;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected MutableClock clock;

    @BeforeEach
    void resetState() {
        jdbc.update("DELETE FROM t_safety_event");
        jdbc.update("DELETE FROM t_command");
        jdbc.update("DELETE FROM t_active_claim");
        jdbc.update("DELETE FROM t_lease");
        jdbc.update("DELETE FROM t_controller");
        jdbc.update("DELETE FROM t_resource");
        jdbc.update("UPDATE t_estop_state SET active = FALSE, triggered_at_ms = NULL, recovered_at_ms = NULL "
                + "WHERE id = 1");
        clock.set(START_MS);
    }
}
