package com.example.starter;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 集成测试基类：每个用例前清理业务表并将回路恢复为合成初始状态，避免顺序依赖；
 * 测试类结束后显式释放命名内存库（DB_CLOSE_DELAY=-1 不会自动释放）。
 */
@Import(TestClockConfig.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class BaseIntegrationTest {

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected MutableClock clock;

    @BeforeEach
    void resetState() {
        jdbc.update("DELETE FROM write_request");
        jdbc.update("DELETE FROM dimming_command");
        jdbc.update("DELETE FROM dimming_record");
        jdbc.update("DELETE FROM accident_event");
        jdbc.update("DELETE FROM traffic_sample");
        jdbc.update("DELETE FROM daily_settlement");
        jdbc.update("UPDATE circuit SET current_level = initial_level, status = 'NORMAL',"
                + " locked_level = NULL, last_adjusted_at = NULL, transition_until = NULL");
        clock.setInstant(TestClockConfig.BASE_TIME);
    }

    @AfterAll
    void releaseDatabase() {
        jdbc.execute("SHUTDOWN");
    }
}
