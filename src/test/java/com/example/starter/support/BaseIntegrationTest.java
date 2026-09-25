package com.example.starter.support;

import com.example.starter.StarterApplication;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;

/**
 * 集成测试基座：真实 Spring 上下文 + H2（MySQL 兼容模式）。
 * 每个用例前清空业务表并写入合成塔机/重叠区，避免用例间顺序依赖。
 */
@SpringBootTest(classes = {StarterApplication.class, TestClockConfig.class})
public abstract class BaseIntegrationTest {

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected Clock clock;

    @BeforeEach
    void resetDatabase() {
        jdbc.update("DELETE FROM brake_command");
        jdbc.update("DELETE FROM breach_event");
        jdbc.update("DELETE FROM pose_reading");
        jdbc.update("DELETE FROM zone_authorization");
        jdbc.update("DELETE FROM overlap_zone");
        jdbc.update("DELETE FROM tower_crane");
        jdbc.update("INSERT INTO tower_crane (id, name, rated_load_tonnes) VALUES "
                + "('CRANE-A','塔机A',8.000),('CRANE-B','塔机B',8.000),('CRANE-C','塔机C',12.000)");
        jdbc.update("INSERT INTO overlap_zone (id, name, center_x_m, center_y_m, radius_m) VALUES "
                + "('ZONE-1','A/B回转重叠区',0.0,0.0,10.0),('ZONE-2','B/C回转重叠区',50.0,0.0,10.0)");
        ((MutableClock) clock).set(TestClockConfig.BASE);
    }

    protected long count(String table) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return n == null ? 0 : n;
    }
}
