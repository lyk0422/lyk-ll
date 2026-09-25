package com.example.starter.testsupport;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 集成测试基类：每用例前清空全部表并重置可控时钟，保证用例间无顺序依赖。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestClockConfig.class)
public abstract class AbstractIntegrationTest {

    private static final List<String> TABLES = List.of(
            "late_temperature_event",
            "manual_review",
            "evaluation_record",
            "idempotent_request",
            "adjustment_record",
            "valve_opening_version",
            "adjustment_task",
            "temperature_reading",
            "valve",
            "heating_unit",
            "heat_station"
    );

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected MutableClock clock;

    @BeforeEach
    void resetDatabaseAndClock() {
        jdbc.execute("SET REFERENTIAL_INTEGRITY FALSE");
        for (String table : TABLES) {
            jdbc.update("DELETE FROM " + table);
        }
        jdbc.execute("SET REFERENTIAL_INTEGRITY TRUE");
        clock.setInstant(TestClockConfig.TEST_EPOCH);
    }

    /** 创建换热站及 n 个单元，每单元一个阀门，返回站点 ID。 */
    protected long createStation(String stationCode, int units, BigDecimal initialOpening) {
        Instant now = clock.instant();
        jdbc.update("INSERT INTO heat_station (station_code, station_name, created_at) VALUES (?, ?, ?)",
                stationCode, stationCode + "-名称", java.sql.Timestamp.from(now));
        Long stationId = jdbc.queryForObject(
                "SELECT id FROM heat_station WHERE station_code = ?", Long.class, stationCode);
        for (int i = 1; i <= units; i++) {
            String unitCode = String.format("%s-U%02d", stationCode, i);
            jdbc.update("INSERT INTO heating_unit (station_id, unit_code, unit_name, created_at) VALUES (?, ?, ?, ?)",
                    stationId, unitCode, unitCode + "-名称", java.sql.Timestamp.from(now));
            Long unitId = jdbc.queryForObject(
                    "SELECT id FROM heating_unit WHERE station_id = ? AND unit_code = ?",
                    Long.class, stationId, unitCode);
            jdbc.update("INSERT INTO valve (unit_id, valve_code, opening, updated_at) VALUES (?, ?, ?, ?)",
                    unitId, "V-" + unitCode, initialOpening, java.sql.Timestamp.from(now));
        }
        return stationId;
    }

    protected long createStation(String stationCode, int units) {
        return createStation(stationCode, units, new BigDecimal("50.000"));
    }

    protected BigDecimal valveOpening(String stationCode, String unitCode) {
        return jdbc.queryForObject(
                "SELECT v.opening FROM valve v JOIN heating_unit u ON u.id = v.unit_id "
                        + "JOIN heat_station s ON s.id = u.station_id "
                        + "WHERE s.station_code = ? AND u.unit_code = ?",
                BigDecimal.class, stationCode, unitCode);
    }

    protected long count(String table) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return count == null ? 0 : count;
    }
}
