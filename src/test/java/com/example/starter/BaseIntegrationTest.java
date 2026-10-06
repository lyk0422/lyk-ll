package com.example.starter;

import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 集成测试基类：独立 H2 内存库（urban_tree_test），可控时钟，
 * 每个用例前清空全部业务表，避免用例间顺序依赖。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ClockTestConfiguration.class)
public abstract class BaseIntegrationTest {

    /** 基准时间：2026-10-06 10:00:00 Asia/Shanghai。 */
    protected static final Instant T0 = Instant.parse("2026-10-06T02:00:00Z");

    @Autowired
    protected MutableClock clock;

    @Autowired
    protected JdbcTemplate jdbc;

    @BeforeEach
    void resetState() {
        clock.setInstant(T0);
        jdbc.update("DELETE FROM work_report");
        jdbc.update("DELETE FROM segment_freeze");
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM work_permit");
        jdbc.update("DELETE FROM pruning_plan");
        jdbc.update("DELETE FROM gale_warning");
        jdbc.update("DELETE FROM tree");
        jdbc.update("DELETE FROM road_segment");
    }
}
