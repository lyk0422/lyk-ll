package com.example.starter;

import com.example.starter.service.BalanceService;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 集成测试基类：每个用例前清空并重置合成数据（1 站、4 单元、4 阀门，初始开度 50%），
 * 并将可控时钟归零到 T0，避免用例间顺序依赖。
 */
@SpringBootTest
@Import(TestClockConfig.class)
public abstract class IntegrationTestBase {

    /** 测试时间零点（UTC）。 */
    protected static final Instant T0 = Instant.parse("2026-09-26T00:00:00Z");
    /** 换热站 ID。 */
    protected static final long STATION_ID = 1L;

    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    protected MutableClock clock;
    @Autowired
    protected BalanceService balanceService;

    @BeforeEach
    void resetDatabase() {
        jdbc.update("DELETE FROM unseal_quota_event");
        jdbc.update("DELETE FROM unseal_permit");
        jdbc.update("DELETE FROM valve_seal");
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM late_temperature_event");
        jdbc.update("DELETE FROM assessment");
        jdbc.update("DELETE FROM valve_opening_version");
        jdbc.update("DELETE FROM temperature_reading");
        jdbc.update("DELETE FROM adjustment_task");
        jdbc.update("DELETE FROM valve");
        jdbc.update("DELETE FROM heating_unit");
        jdbc.update("DELETE FROM heat_station");
        jdbc.update("ALTER TABLE adjustment_task ALTER COLUMN id RESTART WITH 1");
        jdbc.update("ALTER TABLE valve_opening_version ALTER COLUMN id RESTART WITH 1");
        jdbc.update("ALTER TABLE assessment ALTER COLUMN id RESTART WITH 1");
        jdbc.update("ALTER TABLE valve_seal ALTER COLUMN id RESTART WITH 1");
        jdbc.update("ALTER TABLE unseal_permit ALTER COLUMN id RESTART WITH 1");
        jdbc.update("ALTER TABLE unseal_quota_event ALTER COLUMN id RESTART WITH 1");
        jdbc.update("INSERT INTO heat_station (id, code, name) VALUES (1, 'ST-EAST-01', '城东一号换热站')");
        jdbc.update("INSERT INTO heating_unit (id, station_id, code, name) VALUES"
                + " (1, 1, 'U1', '一号单元'), (2, 1, 'U2', '二号单元'),"
                + " (3, 1, 'U3', '三号单元'), (4, 1, 'U4', '四号单元')");
        jdbc.update("INSERT INTO valve (id, unit_id, opening_percent, updated_at) VALUES"
                + " (1, 1, 50.00, TIMESTAMP '2026-01-01 00:00:00'),"
                + " (2, 2, 50.00, TIMESTAMP '2026-01-01 00:00:00'),"
                + " (3, 3, 50.00, TIMESTAMP '2026-01-01 00:00:00'),"
                + " (4, 4, 50.00, TIMESTAMP '2026-01-01 00:00:00')");
        clock.set(T0);
    }

    /**
     * 按单元 1..N 顺序在当前时钟时刻上报温度。
     */
    protected void submitReadings(double... temperatures) {
        submitReadingsAt(clock.instant(), temperatures);
    }

    /**
     * 按单元 1..N 顺序在指定采集时刻上报温度。
     */
    protected void submitReadingsAt(Instant recordedAt, double... temperatures) {
        List<BalanceService.ReadingCommand> commands = new ArrayList<>();
        for (int i = 0; i < temperatures.length; i++) {
            commands.add(new BalanceService.ReadingCommand(
                    i + 1L, BigDecimal.valueOf(temperatures[i]), recordedAt));
        }
        balanceService.submitReadings(STATION_ID, commands);
    }

    /**
     * 查询阀门当前开度。
     */
    protected BigDecimal openingOf(long unitId) {
        return jdbc.queryForObject(
                "SELECT opening_percent FROM valve WHERE unit_id = ?", BigDecimal.class, unitId);
    }

    /**
     * 统计表行数。
     */
    protected int countOf(String table) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return count == null ? 0 : count;
    }
}
