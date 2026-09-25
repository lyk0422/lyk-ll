package com.example.starter;

import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 集成测试基类：独立 H2 内存库（MySQL 兼容模式），每用例前清库并重建合成人员数据。
 */
@SpringBootTest
@Import(TestClockConfig.class)
public abstract class BaseIntegrationTest {

    /** 测试基准时间。 */
    protected static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected MutableClock clock;

    @BeforeEach
    void resetDatabase() {
        jdbc.update("DELETE FROM rescue_event");
        jdbc.update("DELETE FROM elevator_stop_log");
        jdbc.update("DELETE FROM maintenance_record");
        jdbc.update("DELETE FROM rescue_case");
        jdbc.update("DELETE FROM elevator");
        jdbc.update("DELETE FROM staff");
        jdbc.update("INSERT INTO staff (name, cert_no, created_at) VALUES ('张三', 'CERT-A001', CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO staff (name, cert_no, created_at) VALUES ('李四', 'CERT-A002', CURRENT_TIMESTAMP)");
        clock.set(T0);
    }
}
