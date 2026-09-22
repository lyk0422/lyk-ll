package com.example.starter.sensor.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * H2 集成测试基类：嵌入式 H2（MODE=MySQL）独立内存库，每个场景前清空全部业务表，
 * 避免用例间顺序依赖；无需外部 MySQL/Docker。
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(MutableClockConfiguration.class)
public abstract class AbstractH2IntegrationTest {

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected MutableClockConfiguration.MutableServiceClock clock;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM frame_input");
        jdbc.update("DELETE FROM fusion_frame");
        jdbc.update("DELETE FROM dropped_message");
        jdbc.update("DELETE FROM sensor_message");
        jdbc.update("DELETE FROM clock_correction");
        clock.setCurrent(1_700_000_000_000L);
    }
}
