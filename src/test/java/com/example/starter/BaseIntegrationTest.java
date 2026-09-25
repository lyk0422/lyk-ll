package com.example.starter;

import com.example.starter.config.ClockConfig;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

/**
 * 集成测试基类：独立 H2 内存库（testdb），每个用例前清空业务表；
 * 提供可控时钟并固定初始业务时间，避免用例间顺序依赖。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestClockConfiguration.class)
public abstract class BaseIntegrationTest {

    /** 固定初始业务时间：2026-09-25（周五）10:00 Asia/Shanghai。 */
    protected static final LocalDateTime BASE_TIME = TestClockConfiguration.BASE_TIME;

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected MutableClock clock;

    @BeforeEach
    void resetState() {
        clock.setInstant(BASE_TIME.atZone(ClockConfig.BUSINESS_ZONE).toInstant());
        jdbc.update("DELETE FROM liquidation_item");
        jdbc.update("DELETE FROM liquidation_order");
        jdbc.update("DELETE FROM deposit");
        jdbc.update("DELETE FROM margin_call");
        jdbc.update("DELETE FROM liability");
        jdbc.update("DELETE FROM snapshot");
        jdbc.update("DELETE FROM account");
    }

    /** 业务时间推进到指定本地时间（Asia/Shanghai）。 */
    protected void advanceTo(LocalDateTime time) {
        clock.setInstant(time.atZone(ClockConfig.BUSINESS_ZONE).toInstant());
    }

    protected long queryLong(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }
}
