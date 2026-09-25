package com.example.starter.deicing;

import com.example.starter.deicing.service.DeicingService;
import com.example.starter.deicing.testsupport.MutableServiceClock;
import com.example.starter.deicing.testsupport.TestClockConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 集成测试基类：嵌入式 H2（MySQL 模式）+ 可控时钟 + 场景间清表。
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestClockConfiguration.class)
public abstract class AbstractDeicingIntegrationTest {

    /** 固定基准时刻：2023-11-14T22:13:20Z，epoch 毫秒。 */
    protected static final long BASE = 1_700_000_000_000L;
    protected static final long MIN = 60_000L;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected MutableServiceClock clock;

    @Autowired
    protected DeicingService service;

    /**
     * 每个场景前清空业务数据（保留合成基础数据）并复位时钟，避免顺序依赖。
     */
    @BeforeEach
    void cleanDatabaseAndClock() {
        jdbcTemplate.update("DELETE FROM deicing_assignment");
        jdbcTemplate.update("DELETE FROM idempotent_operation");
        jdbcTemplate.update("DELETE FROM queue_request");
        jdbcTemplate.update("DELETE FROM flight_active");
        clock.setCurrent(BASE);
    }

    /**
     * @param table 表名
     * @return 行数
     */
    protected long countOf(String table) {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return count == null ? 0L : count;
    }
}
