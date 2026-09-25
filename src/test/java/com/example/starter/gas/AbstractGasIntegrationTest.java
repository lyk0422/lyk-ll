package com.example.starter.gas;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * H2 集成测试基类：每个场景前清空全部表并重置自增、恢复合成门站种子，
 * 保证场景之间无顺序依赖。测试不使用外部 MySQL/Docker。
 */
@SpringBootTest
public abstract class AbstractGasIntegrationTest {

    protected static final String STATION = "ST-001";

    @Autowired
    protected JdbcTemplate jdbc;

    @BeforeEach
    void resetDatabase() {
        jdbc.execute("SET REFERENTIAL_INTEGRITY FALSE");
        for (String table : new String[]{
                "station_event", "late_detection_event", "daily_settlement", "linkage_slot",
                "linkage_task", "pressure_detection", "valve_command", "odorant_injection",
                "flow_reading", "concentration_detection", "idempotent_request", "gate_station"
        }) {
            jdbc.execute("TRUNCATE TABLE " + table + " RESTART IDENTITY");
        }
        jdbc.execute("SET REFERENTIAL_INTEGRITY TRUE");
        jdbc.update("INSERT INTO gate_station (station_code, station_name, odor_target, odor_tolerance, "
                        + "pressure_low, pressure_high, odor_consumed, valve_state, alarm_active, violation_count) "
                        + "VALUES ('ST-001', '城东门站（合成）', 20.0000, 0.2000, 200.0000, 400.0000, "
                        + "0.000000, 'OPEN', FALSE, 0)",
                new Object[0]);
    }

    /** 门站实时状态行（独立连接读取，验证事务提交后的最终数据）。 */
    protected java.util.Map<String, Object> stationSnapshot() {
        return jdbc.queryForMap("SELECT valve_state, alarm_active, violation_count, odor_consumed "
                + "FROM gate_station WHERE station_code = 'ST-001'");
    }

    protected int countOf(String table, String where, Object... args) {
        Integer n = jdbc.queryForObject("SELECT COUNT(1) FROM " + table + " WHERE " + where,
                Integer.class, args);
        return n == null ? 0 : n;
    }
}
