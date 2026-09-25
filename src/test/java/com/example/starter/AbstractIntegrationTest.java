package com.example.starter;

import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

/**
 * H2（MODE=MySQL）集成测试基类：每个场景后按外栏关系清理数据，避免顺序依赖。
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    private static final List<String> TABLES = List.of(
            "idempotent_request",
            "retest",
            "ventilation_operation",
            "fan_active",
            "late_reading_event",
            "sensor_reading",
            "alarm",
            "silo",
            "fan");

    @Autowired
    protected JdbcTemplate jdbc;

    @AfterEach
    void cleanUp() {
        jdbc.update("SET REFERENTIAL_INTEGRITY FALSE");
        for (String table : TABLES) {
            jdbc.update("DELETE FROM " + table);
        }
        jdbc.update("SET REFERENTIAL_INTEGRITY TRUE");
    }
}
