package com.example.starter.testsupport;

import com.example.starter.testsupport.MutableClock.Config;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.junit.jupiter.api.BeforeEach;

import java.time.Instant;
import java.util.List;

/**
 * H2 集成测试基类：每个场景前清空全部业务表（关闭外键检查后按表 TRUNCATE），
 * 并将可控时钟复位为固定时刻，保证场景间无顺序依赖。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(Config.class)
public abstract class AbstractIntegrationTest {

    private static final Instant BASE_TIME = Instant.parse("2026-01-01T00:00:00Z");

    private static final List<String> TABLES = List.of(
            "order_event",
            "dispatch_reassignment",
            "generator_active_order",
            "station_active_order",
            "dispatch_order",
            "capacity_reading",
            "outage_event",
            "generator",
            "base_station",
            "idempotent_request"
    );

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected MutableClock clock;

    @BeforeEach
    void cleanDatabase() {
        jdbc.execute("SET REFERENTIAL_INTEGRITY FALSE");
        for (String table : TABLES) {
            jdbc.execute("TRUNCATE TABLE " + table);
        }
        jdbc.execute("SET REFERENTIAL_INTEGRITY TRUE");
        clock.set(BASE_TIME);
    }

    protected int count(String table) {
        Integer value = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return value == null ? 0 : value;
    }
}
