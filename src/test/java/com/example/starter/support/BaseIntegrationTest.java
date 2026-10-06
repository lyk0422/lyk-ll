package com.example.starter.support;

import java.time.Instant;
import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.DriverManager;

/**
 * 集成测试基类：真实 H2（MODE=MySQL）+ 可控时钟；每个用例前清空业务表并重置时钟。
 * 测试上下文关闭时释放内存数据库。
 */
@SpringBootTest
@Import(BaseIntegrationTest.TestConfig.class)
public abstract class BaseIntegrationTest {

    /** 用例默认当前时刻（UTC） */
    protected static final Instant DEFAULT_NOW = Instant.parse("2026-10-06T08:00:00Z");

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected MutableClock clock;

    @BeforeEach
    void resetState() {
        clock.setInstant(DEFAULT_NOW);
        jdbc.update("DELETE FROM daily_settlement");
        jdbc.update("DELETE FROM reassignment");
        jdbc.update("DELETE FROM route_status_log");
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM notification");
        jdbc.update("DELETE FROM check_in");
        jdbc.update("DELETE FROM qualification_renewal");
        jdbc.update("DELETE FROM qualification");
        jdbc.update("DELETE FROM assessment");
        jdbc.update("DELETE FROM route_student");
        jdbc.update("DELETE FROM route");
        jdbc.update("DELETE FROM student");
        jdbc.update("DELETE FROM vehicle");
    }

    protected void insertVehicle(long id, int seats) {
        jdbc.update("INSERT INTO vehicle (id, plate_no, seats) VALUES (?,?,?)",
                id, "V-" + id, seats);
    }

    protected void insertStudent(long id) {
        jdbc.update("INSERT INTO student (id, student_no, name) VALUES (?,?,?)",
                id, "S-" + id, "合成学生" + id);
    }

    protected void insertRoute(long id, Long vehicleId, String status) {
        jdbc.update("INSERT INTO route (id, code, vehicle_id, status) VALUES (?,?,?,?)",
                id, "R-" + id, vehicleId, status);
    }

    protected void assignStudent(long routeId, long studentId) {
        jdbc.update("INSERT INTO route_student (route_id, student_id) VALUES (?,?)", routeId, studentId);
    }

    protected void insertQualification(long routeId, LocalDate validFrom, LocalDate validTo, String status) {
        jdbc.update("INSERT INTO qualification (route_id, valid_from, valid_to, status) VALUES (?,?,?,?)",
                routeId, validFrom, validTo, status);
    }

    protected int count(String table) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return count == null ? 0 : count;
    }

    protected LocalDate today() {
        return LocalDate.ofInstant(DEFAULT_NOW, java.time.ZoneOffset.UTC);
    }

    /**
     * 测试配置：可控时钟替代系统时钟；上下文关闭时释放 H2 内存库。
     */
    @TestConfiguration
    static class TestConfig {

        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(DEFAULT_NOW);
        }

        @Bean
        DisposableBean h2DatabaseRelease(@Value("${spring.datasource.url}") String url) {
            return () -> {
                try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
                    connection.createStatement().execute("SHUTDOWN");
                } catch (Exception ignored) {
                    // 上下文关闭阶段尽力释放；JVM 退出后内存库随之消失
                }
            };
        }
    }
}
